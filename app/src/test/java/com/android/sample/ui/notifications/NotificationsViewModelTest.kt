package com.android.sample.ui.notifications

// AI assistance: OpenAI Codex.
import androidx.lifecycle.viewModelScope
import com.android.sample.MainDispatcherRule
import com.android.sample.data.repository.*
import com.android.sample.model.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises confirmed updates, independent pages and replies that cross load/session changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val identity = MutableStateFlow<String?>("alice")
  private val notice =
      OrderNotification(
          OrderNotificationEvent(
              "order %+#42",
              "alice",
              NotificationEventType.ORDER_ACCEPTED,
              Instant.EPOCH,
          )
      )
  private val repository = RecordingNotifications(listOf(notice))
  private val models = mutableListOf<NotificationsViewModel>()

  private fun model() =
      NotificationsViewModel(repository, { identity.value }, identity).also { models += it }

  @After fun closeModels() = models.forEach { it.viewModelScope.cancel() }

  @Test
  fun retryReadUpdateRepeatsTheFailedIdAndClearsItsError() = runTest {
    repository.mark = { OrderResult.Error(OrderError.Unknown) }
    val vm = model()
    vm.refreshInbox()
    advanceUntilIdle()
    vm.markAsRead(notice.event.id)
    advanceUntilIdle()
    assertFalse((vm.inbox.value as NotificationPageState.Content).value.single().isRead)
    repository.mark = { OrderResult.Success(Unit) }
    vm.retryReadUpdate()
    advanceUntilIdle()
    assertEquals(NotificationPageState.Content(listOf(notice.copy(isRead = true))), vm.inbox.value)
    assertEquals(listOf(notice.event.id, notice.event.id), repository.markedIds)
  }

  @Test
  fun retryPreferencesKeepsTheFailedSnapshotAndCannotCrossAccounts() = runTest {
    val settings = NotificationPreferences(false, false)
    repository.savePreferences = { OrderResult.Error(OrderError.Unknown) }
    val vm = model()
    vm.refreshPreferences()
    advanceUntilIdle()
    vm.updatePreferences(settings)
    advanceUntilIdle()
    repository.savePreferences = { OrderResult.Success(Unit) }
    vm.retryPreferenceUpdate()
    advanceUntilIdle()
    assertEquals(NotificationPageState.Content(settings), vm.preferences.value)
    vm.updatePreferences(NotificationPreferences())
    advanceUntilIdle()
    identity.value = "bob"
    vm.retryPreferenceUpdate()
    advanceUntilIdle()
    assertEquals(NotificationPageState.SessionEnded, vm.preferences.value)
  }

  @Test
  fun inboxPreservesRepositoryOrderAndDoesNotLoadPreferences() = runTest {
    val read = notice.copy(event = notice.event.copy(orderId = "older"), isRead = true)
    repository.entries = listOf(notice, read)
    val vm = model()
    assertEquals(NotificationPageState.Loading, vm.inbox.value)
    vm.refreshInbox()
    advanceUntilIdle()
    assertEquals(NotificationPageState.Content(listOf(notice, read)), vm.inbox.value)
    assertEquals(0, repository.preferenceReads)
  }

  @Test
  fun emptyInboxIsAConfirmedEmptyList() = runTest {
    repository.entries = emptyList()
    val vm = model()
    vm.refreshInbox()
    advanceUntilIdle()
    assertEquals(NotificationPageState.Content(emptyList<OrderNotification>()), vm.inbox.value)
  }

  @Test
  fun inboxFailureCanRetryWithoutBlockingPreferences() = runTest {
    repository.loadInbox = { OrderResult.Error(OrderError.Unknown) }
    val vm = model()
    vm.refreshInbox()
    vm.refreshPreferences()
    advanceUntilIdle()
    assertEquals(NotificationPageState.Error(OrderError.Unknown), vm.inbox.value)
    assertEquals(NotificationPageState.Content(NotificationPreferences()), vm.preferences.value)
    repository.loadInbox = { OrderResult.Success(repository.entries) }
    vm.refreshInbox()
    advanceUntilIdle()
    assertEquals(NotificationPageState.Content(listOf(notice)), vm.inbox.value)
  }

  @Test
  fun supersededLoadCannotReplaceTheLatestResponse() = runTest {
    val delayed = CompletableDeferred<OrderResult<List<OrderNotification>>>()
    repository.loadInbox = { withContext(NonCancellable) { delayed.await() } }
    val vm = model()
    vm.refreshInbox()
    runCurrent()
    repository.loadInbox = { OrderResult.Success(emptyList()) }
    vm.refreshInbox()
    runCurrent()
    delayed.complete(OrderResult.Success(listOf(notice)))
    advanceUntilIdle()
    assertEquals(NotificationPageState.Content(emptyList<OrderNotification>()), vm.inbox.value)
  }

  @Test
  fun reloadDuringReadSaveWaitsAndKeepsTheConfirmedReadState() = runTest {
    val saved = CompletableDeferred<OrderResult<Unit>>()
    repository.mark = { saved.await() }
    val vm = model()
    vm.refreshInbox()
    advanceUntilIdle()
    vm.markAsRead(notice.event.id)
    runCurrent()
    vm.refreshInbox()
    vm.refreshInbox()
    vm.markAsRead(notice.event.id)
    assertEquals(NotificationPageState.Content(listOf(notice), isSaving = true), vm.inbox.value)
    assertEquals(1, repository.inboxReads)
    saved.complete(OrderResult.Success(Unit))
    advanceUntilIdle()
    assertEquals(NotificationPageState.Content(listOf(notice.copy(isRead = true))), vm.inbox.value)
    assertEquals(listOf(notice.event.id), repository.markedIds)
    assertEquals(2, repository.inboxReads)
  }

  @Test
  fun failedReadSaveKeepsUnreadStateAndAllowsRetry() = runTest {
    val vm = model()
    vm.refreshInbox()
    advanceUntilIdle()
    repository.mark = { OrderResult.Error(OrderError.Unknown) }
    vm.markAsRead(notice.event.id)
    vm.refreshInbox()
    advanceUntilIdle()
    assertEquals(
        NotificationPageState.Content(listOf(notice), saveError = OrderError.Unknown),
        vm.inbox.value,
    )
    repository.mark = { OrderResult.Success(Unit) }
    vm.markAsRead(notice.event.id)
    advanceUntilIdle()
    assertEquals(NotificationPageState.Content(listOf(notice.copy(isRead = true))), vm.inbox.value)
    vm.markAsRead(notice.event.id)
    vm.markAsRead("unknown")
    assertEquals(2, repository.markedIds.size)
  }

  @Test
  fun preferenceSaveSurvivesConcurrentRefreshAndReopening() = runTest {
    val saved = CompletableDeferred<OrderResult<Unit>>()
    repository.savePreferences = { saved.await() }
    val vm = model()
    vm.refreshPreferences()
    advanceUntilIdle()
    val changed = NotificationPreferences(newOrders = false)
    vm.updatePreferences(changed)
    runCurrent()
    vm.refreshPreferences()
    vm.updatePreferences(NotificationPreferences(orderDecisions = false))
    assertEquals(
        NotificationPageState.Content(NotificationPreferences(), isSaving = true),
        vm.preferences.value,
    )
    saved.complete(OrderResult.Success(Unit))
    advanceUntilIdle()
    assertEquals(listOf(changed), repository.savedSettings)
    val reopened = model()
    reopened.refreshPreferences()
    advanceUntilIdle()
    assertEquals(NotificationPageState.Content(changed), reopened.preferences.value)
    reopened.updatePreferences(changed)
    assertEquals(1, repository.savedSettings.size)
    assertEquals(0, repository.inboxReads)
  }

  @Test
  fun preferenceFailuresKeepConfirmedValuesAndRetry() = runTest {
    repository.loadPreferences = { OrderResult.Error(OrderError.Unknown) }
    val vm = model()
    vm.refreshPreferences()
    advanceUntilIdle()
    assertEquals(NotificationPageState.Error(OrderError.Unknown), vm.preferences.value)
    repository.loadPreferences = { OrderResult.Success(repository.settings) }
    vm.refreshPreferences()
    advanceUntilIdle()
    val changed = NotificationPreferences(orderDecisions = false)
    repository.savePreferences = { OrderResult.Error(OrderError.Unknown) }
    vm.updatePreferences(changed)
    advanceUntilIdle()
    assertEquals(
        NotificationPageState.Content(NotificationPreferences(), saveError = OrderError.Unknown),
        vm.preferences.value,
    )
    repository.savePreferences = { OrderResult.Success(Unit) }
    vm.updatePreferences(changed)
    advanceUntilIdle()
    assertEquals(NotificationPageState.Content(changed), vm.preferences.value)
  }

  @Test
  fun signOutClearsBothPagesEvenWhenTheOriginalAccountReturns() = runTest {
    val delayed = CompletableDeferred<OrderResult<Unit>>()
    repository.mark = { withContext(NonCancellable) { delayed.await() } }
    val vm = model()
    vm.refreshInbox()
    vm.refreshPreferences()
    advanceUntilIdle()
    vm.markAsRead(notice.event.id)
    runCurrent()
    identity.value = null
    runCurrent()
    identity.value = "alice"
    runCurrent()
    delayed.complete(OrderResult.Success(Unit))
    advanceUntilIdle()
    vm.refreshInbox()
    vm.refreshPreferences()
    assertEquals(NotificationPageState.SessionEnded, vm.inbox.value)
    assertEquals(NotificationPageState.SessionEnded, vm.preferences.value)
  }

  @Test
  fun accountSnapshotChangeBeforeTapPreventsAnyWrite() = runTest {
    val vm = model()
    vm.refreshInbox()
    advanceUntilIdle()
    identity.value = "bob"
    vm.markAsRead(notice.event.id)
    assertTrue(repository.markedIds.isEmpty())
    assertEquals(NotificationPageState.SessionEnded, vm.inbox.value)
    assertEquals(NotificationPageState.SessionEnded, vm.preferences.value)
  }

  @Test
  fun unchangedPreferenceTapStillClearsAnEndedAccount() = runTest {
    val vm = model()
    vm.refreshPreferences()
    advanceUntilIdle()
    identity.value = "bob"
    vm.updatePreferences(repository.settings)
    assertTrue(repository.savedSettings.isEmpty())
    assertEquals(NotificationPageState.SessionEnded, vm.inbox.value)
    assertEquals(NotificationPageState.SessionEnded, vm.preferences.value)
  }

  @Test
  fun foreignRowsAndUnexpectedExceptionsFailClosed() = runTest {
    repository.entries = listOf(notice.copy(event = notice.event.copy(recipientId = "bob")))
    val vm = model()
    vm.refreshInbox()
    advanceUntilIdle()
    assertEquals(NotificationPageState.Error(OrderError.PermissionDenied), vm.inbox.value)
    repository.loadInbox = { throw IllegalStateException("fixture failure") }
    vm.refreshInbox()
    advanceUntilIdle()
    assertEquals(NotificationPageState.Error(OrderError.Unknown), vm.inbox.value)
  }

  @Test
  fun missingSessionDoesNotLoadAnyProtectedData() = runTest {
    identity.value = null
    val vm = model()
    vm.refreshInbox()
    vm.refreshPreferences()
    advanceUntilIdle()
    assertEquals(NotificationPageState.SessionEnded, vm.inbox.value)
    assertEquals(NotificationPageState.SessionEnded, vm.preferences.value)
    assertEquals(0, repository.inboxReads + repository.preferenceReads)
  }
}

/** Records interface calls and injects suspended responses; it does not emulate Firebase rules. */
private class RecordingNotifications(var entries: List<OrderNotification>) :
    NotificationRepository {
  var settings = NotificationPreferences()
  var inboxReads = 0
  var preferenceReads = 0
  val markedIds = mutableListOf<String>()
  val savedSettings = mutableListOf<NotificationPreferences>()
  var loadInbox: suspend () -> OrderResult<List<OrderNotification>> = {
    OrderResult.Success(entries)
  }
  var loadPreferences: suspend () -> OrderResult<NotificationPreferences> = {
    OrderResult.Success(settings)
  }
  var mark: suspend (String) -> OrderResult<Unit> = { OrderResult.Success(Unit) }
  var savePreferences: suspend (NotificationPreferences) -> OrderResult<Unit> = {
    OrderResult.Success(Unit)
  }

  override suspend fun getNotifications() = loadInbox().also { inboxReads++ }

  override suspend fun getPreferences() = loadPreferences().also { preferenceReads++ }

  override suspend fun markAsRead(notificationId: String) =
      mark(notificationId).also {
        markedIds += notificationId
        if (it is OrderResult.Success)
            entries = entries.map { entry ->
              if (entry.event.id == notificationId) entry.copy(isRead = true) else entry
            }
      }

  override suspend fun updatePreferences(preferences: NotificationPreferences) =
      savePreferences(preferences).also {
        savedSettings += preferences
        if (it is OrderResult.Success) settings = preferences
      }
}
