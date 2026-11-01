package com.android.sample.ui.notifications

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.NotificationRepository
import com.android.sample.data.repository.OrderError
import com.android.sample.data.repository.OrderResult
import com.android.sample.model.NotificationPreferences
import com.android.sample.model.OrderNotification
import com.android.sample.ui.session.SessionGuard
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Independent state for either notification page; an empty inbox is Content with an empty list. */
sealed interface NotificationPageState<out T> {
  /** A page load is in progress, without keeping stale account data in this state. */
  data object Loading : NotificationPageState<Nothing>

  /** Only confirmed values are shown; failed saves keep them and expose a retryable error. */
  data class Content<T>(
      val value: T,
      val isSaving: Boolean = false,
      val saveError: OrderError? = null,
  ) : NotificationPageState<T>

  /** A failed load, retryable through the corresponding page refresh. */
  data class Error(val error: OrderError) : NotificationPageState<Nothing>

  /** Terminal for this instance; a new authenticated graph creates its replacement. */
  data object SessionEnded : NotificationPageState<Nothing>
}

/**
 * Session-bound inbox and Profile preferences. Pages load independently when navigation requests
 * them. Reloads during a save wait for its result, preventing stale read state or settings.
 */
class NotificationsViewModel(
    private val repository: NotificationRepository,
    currentUserId: () -> String?,
    sessionChanges: Flow<String?>,
) : ViewModel() {
  private val session = SessionGuard(currentUserId)
  private val inboxPage = Page {
    when (val result = repository.getNotifications()) {
      is OrderResult.Success ->
          if (result.data.any { it.event.recipientId != session.initialUserId })
              OrderResult.Error(OrderError.PermissionDenied)
          else result
      is OrderResult.Error -> result
    }
  }
  private val preferencesPage = Page(repository::getPreferences)

  /** Inbox entries in repository order; a failed read update never optimistically marks them. */
  val inbox: StateFlow<NotificationPageState<List<OrderNotification>>> = inboxPage.uiState

  /** Account preferences, independent of inbox loading failures. */
  val preferences: StateFlow<NotificationPageState<NotificationPreferences>> =
      preferencesPage.uiState

  init {
    viewModelScope.launch {
      sessionChanges.collect { uid ->
        if (session.hasEnded(uid) || session.initialUserId.isNullOrBlank()) endSession()
      }
    }
  }

  /** Loads or retries the inbox; navigation may call this on resume. */
  fun refreshInbox() = inboxPage.refresh()

  /** Restores the persisted preferences when their page opens or retries. */
  fun refreshPreferences() = preferencesPage.refresh()

  /** Retries the last failed read-state update while the same content and session are active. */
  fun retryReadUpdate() = inboxPage.retry()

  /** Retries the last failed preference snapshot without asking the UI to retain it. */
  fun retryPreferenceUpdate() = preferencesPage.retry()

  /** Marks a currently visible unread entry; ignores unknown, read or already-saving entries. */
  fun markAsRead(notificationId: String) {
    if (!active()) return
    val content = inbox.value as? NotificationPageState.Content ?: return
    if (content.value.none { it.event.id == notificationId && !it.isRead }) return
    inboxPage.save({ repository.markAsRead(notificationId) }) { entries ->
      entries.map { if (it.event.id == notificationId) it.copy(isRead = true) else it }
    }
  }

  /** Saves an entire preference snapshot; confirmed settings stay visible until success. */
  fun updatePreferences(settings: NotificationPreferences) {
    if (!active()) return
    if ((preferences.value as? NotificationPageState.Content)?.value == settings) return
    preferencesPage.save({ repository.updatePreferences(settings) }) { settings }
  }

  private fun active(): Boolean =
      if (session.initialUserId.isNullOrBlank() || session.hasEnded()) {
        endSession()
        false
      } else true

  private fun endSession() {
    inboxPage.end()
    preferencesPage.end()
  }

  /** Shared lifecycle for the two pages, without sharing their data, loading or failure state. */
  private inner class Page<T>(private val read: suspend () -> OrderResult<T>) {
    private val state = MutableStateFlow<NotificationPageState<T>>(NotificationPageState.Loading)
    val uiState: StateFlow<NotificationPageState<T>> = state.asStateFlow()
    private var job: Job? = null
    private var refreshAfterSave = false
    private var retrySave: (() -> Unit)? = null

    fun retry() {
      if ((state.value as? NotificationPageState.Content)?.saveError != null) retrySave?.invoke()
    }

    fun refresh() {
      if (!active()) return
      if ((state.value as? NotificationPageState.Content)?.isSaving == true) {
        refreshAfterSave = true
        return
      }
      job?.cancel()
      retrySave = null
      state.value = NotificationPageState.Loading
      job = viewModelScope.launch {
        val result = request(read)
        if (active()) {
          state.value =
              when (result) {
                is OrderResult.Success -> NotificationPageState.Content(result.data)
                is OrderResult.Error -> NotificationPageState.Error(result.error)
              }
        }
      }
    }

    fun save(write: suspend () -> OrderResult<Unit>, confirmed: (T) -> T) {
      if (!active()) return
      val content = state.value as? NotificationPageState.Content ?: return
      if (content.isSaving) return
      retrySave = { save(write, confirmed) }
      state.value = content.copy(isSaving = true, saveError = null)
      job = viewModelScope.launch {
        val result = request(write)
        if (!active()) return@launch
        if (result is OrderResult.Success) retrySave = null
        state.value =
            when (result) {
              is OrderResult.Success -> NotificationPageState.Content(confirmed(content.value))
              is OrderResult.Error -> content.copy(saveError = result.error)
            }
        val reload = refreshAfterSave
        refreshAfterSave = false
        // Preserve visible failure feedback; a successful save may now reload authoritative data.
        if (reload && result is OrderResult.Success) refresh()
      }
    }

    fun end() {
      job?.cancel()
      retrySave = null
      state.value = NotificationPageState.SessionEnded
    }
  }

  private suspend fun <T> request(action: suspend () -> OrderResult<T>): OrderResult<T> {
    val result =
        try {
          action()
        } catch (_: Exception) {
          currentCoroutineContext().ensureActive()
          OrderResult.Error(OrderError.Unknown)
        }
    currentCoroutineContext().ensureActive()
    return result
  }
}
