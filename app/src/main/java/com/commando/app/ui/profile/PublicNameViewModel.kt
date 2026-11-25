package com.commando.app.ui.profile

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.commando.app.data.repository.PublicProfileRepository
import com.commando.app.data.repository.isValidPublicName
import com.commando.app.ui.session.SessionGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One Edit Profile entry's public-name editor, independent of private profile and photo state.
 * Reads and UI waits end with this entry; submitted writes belong to the root-scoped [writes].
 */
class PublicNameViewModel(
    private val profiles: Lazy<PublicProfileRepository>,
    private val writes: PublicNameWritesViewModel,
    currentUserId: () -> String?,
    sessionChanges: Flow<String?>,
) : ViewModel() {
  private val session = SessionGuard(currentUserId)
  private val mutableState = MutableStateFlow<PublicNameEditState?>(null)
  /** Null until explicitly opened; confirmed values never include pending or rejected writes. */
  val publicName = mutableState.asStateFlow()
  private var operation: Job? = null

  init {
    viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
      sessionChanges.collect { uid ->
        if (session.hasEnded(uid)) {
          operation?.cancel()
          mutableState.value = null
        }
      }
    }
  }

  /** Opens or retries the editor, reconciling outstanding writes before reading committed data. */
  fun loadPublicName() {
    if (mutableState.value?.loaded == true) return
    operate()
  }

  /** Edits only the draft; typing never publishes or copies private authentication names. */
  fun editPublicName(name: String) {
    val state = mutableState.value ?: return
    if (canAct() && state.loaded && !state.busy)
        mutableState.value = state.copy(draft = name, failed = false)
  }

  /**
   * Explicitly publishes/updates [name] unchanged, or removes it when null; also retries failures.
   */
  fun savePublicName(name: String?) {
    val state = mutableState.value ?: return
    if (!canAct() || !state.loaded || state.busy) return
    if (name != null) {
      editPublicName(name)
      if (!isValidPublicName(name) || name == state.confirmed) return
    } else if (state.confirmed == null) return
    operate(write = true, name = name)
  }

  private fun canAct() =
      viewModelScope.isActive && session.initialUserId?.isNotBlank() == true && !session.hasEnded()

  private fun operate(write: Boolean = false, name: String? = null) {
    if (!canAct()) return
    val uid = checkNotNull(session.initialUserId)
    val previous = mutableState.value ?: PublicNameEditState()
    if (previous.busy) return
    val pending = previous.copy(busy = true, failed = false)
    val job =
        viewModelScope.launch(start = CoroutineStart.LAZY) {
          fun finish(state: PublicNameEditState) {
            if (canAct() && mutableState.value === pending) mutableState.value = state
          }
          try {
            if (!canAct()) return@launch
            val confirmed =
                if (write) {
                  writes.submit(uid, profiles, name).await()
                  name
                } else {
                  writes.awaitPending(uid)
                  ensureActive()
                  if (!canAct()) return@launch
                  profiles.value.getDisplayName(uid)
                }
            ensureActive()
            finish(
                PublicNameEditState(
                    loaded = true,
                    confirmed = confirmed,
                    draft = confirmed.orEmpty(),
                )
            )
          } catch (cancelled: CancellationException) {
            if (isActive) finish(previous)
            throw cancelled
          } catch (_: Exception) {
            ensureActive()
            finish(previous.copy(failed = true))
          }
        }
    operation = job
    mutableState.value = pending
    job.start()
  }
}
