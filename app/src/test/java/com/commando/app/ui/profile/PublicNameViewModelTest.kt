package com.commando.app.ui.profile

// AI assistance: OpenAI Codex. Migrated from SessionViewModelTest with entry/root lifecycle
// fixtures.
import androidx.lifecycle.ViewModelStore
import com.android.sample.MainDispatcherRule
import com.commando.app.data.repository.PublicProfileTestRepository
import com.commando.app.model.authentication.AuthUser
import com.commando.app.model.authentication.FakeAuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PublicNameViewModelTest {
  private val alice = AuthUser("alice", email = "alice@epfl.ch")
  private val bob = AuthUser("bob", email = "bob@epfl.ch")
  private val publicAuth = FakeAuthRepository(alice.copy(displayName = "Private name"))
  private val profiles = PublicProfileTestRepository()
  private val rootStore = ViewModelStore()
  private var editorStore = ViewModelStore()
  private val writes by lazy {
    PublicNameWritesViewModel(
            { publicAuth.currentUser?.uid },
            publicAuth.observeAuthState().map { it?.uid },
        )
        .also { rootStore.put("writes", it) }
  }
  private var editor: PublicNameViewModel? = null
  private val names: PublicNameViewModel
    get() = editor ?: replaceEditor()

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @After
  fun tearDown() {
    editorStore.clear()
    rootStore.clear()
  }

  private fun replaceEditor(): PublicNameViewModel {
    editorStore.clear()
    editorStore = ViewModelStore()
    return PublicNameViewModel(
            lazyOf(profiles),
            writes,
            { publicAuth.currentUser?.uid },
            publicAuth.observeAuthState().map { it?.uid },
        )
        .also {
          editorStore.put("editor", it)
          editor = it
        }
  }

  private fun clearEditor() {
    editorStore.clear()
    editor = null
  }

  private fun TestScope.openNames(initial: String? = null) {
    profiles.name = initial
    reopenNames()
  }

  private fun TestScope.reopenNames() {
    replaceEditor().loadPublicName()
    runCurrent()
  }

  private suspend fun TestScope.switchAccount(user: AuthUser?) {
    if (user == null) publicAuth.signOut()
    else {
      publicAuth.signInWithEmailResult = Result.success(user)
      publicAuth.signInWithEmail("", "")
    }
    runCurrent()
  }

  @Test
  fun invalidPublicInputStaysDraftWithoutWriting() = runTest {
    openNames("Confirmed")
    names.savePublicName("\u0085\u00A0")
    assertEquals(true, names.publicName.value?.nameInvalid)
    assertEquals(false, names.publicName.value?.canSave)
    assertEquals("Confirmed", names.publicName.value?.confirmed)
    assertTrue(profiles.writes.isEmpty())
  }

  @Test
  fun pendingWriteKeepsConfirmedNameAndRejectsOverlappingActions() = runTest {
    openNames("Old")
    val reply = profiles.deferWrite()
    names.savePublicName("New")
    names.savePublicName(null)
    names.editPublicName("Ignored")
    runCurrent()
    assertEquals(PublicNameEditState(true, "Old", "New", busy = true), names.publicName.value)
    assertEquals(listOf("New"), profiles.writes)
    reply.complete(Unit)
    runCurrent()
    assertEquals("New", names.publicName.value?.confirmed)
  }

  private fun TestScope.reconcile(
      initial: String?,
      submitted: String?,
      failure: Exception? = null,
  ) {
    openNames(initial)
    val reads = profiles.reads.size
    val reply = profiles.deferWrite()
    names.savePublicName(submitted)
    runCurrent()
    repeat(3) { reopenNames() }
    assertEquals(PublicNameEditState(busy = true), names.publicName.value)
    assertEquals(reads, profiles.reads.size)
    if (failure == null) reply.complete(Unit) else reply.completeExceptionally(failure)
    runCurrent()
    val confirmed = if (failure == null) submitted else initial
    assertEquals(PublicNameEditState(true, confirmed, confirmed.orEmpty()), names.publicName.value)
    names.editPublicName("New draft")
    names.loadPublicName()
    assertEquals("New draft", names.publicName.value?.draft)
  }

  @Test
  fun publicationAndUpdateReconcileAfterRepeatedReopening() = runTest {
    for (initial in listOf(null, "Old")) reconcile(initial, "New")
  }

  @Test fun removalReconcilesAfterReopening() = runTest { reconcile("Old", null) }

  @Test
  fun rejectedWritesReconcileToPersistedNameAfterReopening() = runTest {
    for ((initial, submitted) in listOf(null to "New", "Old" to "New", "Old" to null)) {
      reconcile(initial, submitted, IllegalStateException("Rejected"))
    }
  }

  @Test
  fun providerCancelledWriteRemainsReconcilable() = runTest {
    reconcile("Old", "New", CancellationException("Provider cancelled"))
  }

  @Test
  fun failedReconciliationReadRemainsUnconfirmedUntilRetry() = runTest {
    openNames()
    val reply = profiles.deferWrite()
    names.savePublicName("New")
    runCurrent()
    reopenNames()
    profiles.read = { error("Offline") }
    reply.complete(Unit)
    runCurrent()
    assertEquals(PublicNameEditState(failed = true), names.publicName.value)
    profiles.read = { profiles.name }
    names.loadPublicName()
    runCurrent()
    assertEquals("New", names.publicName.value?.confirmed)
  }

  @Test fun failedPublicationRetainsDraftAndCanRetry() = runTest { retryWrite(null, "New") }

  @Test fun failedUpdateRetainsConfirmedNameAndCanRetry() = runTest { retryWrite("Old", "New") }

  @Test fun failedRemovalRetainsUnsavedDraftAndCanRetry() = runTest { retryWrite("Old", null) }

  private fun TestScope.retryWrite(initial: String?, submitted: String?) {
    openNames(initial)
    names.editPublicName("Unsaved")
    profiles.write = { error("Rejected") }
    names.savePublicName(submitted)
    runCurrent()
    assertEquals(
        PublicNameEditState(true, initial, submitted ?: "Unsaved", failed = true),
        names.publicName.value,
    )
    profiles.write = { profiles.name = it }
    names.savePublicName(submitted)
    runCurrent()
    assertEquals(PublicNameEditState(true, submitted, submitted.orEmpty()), names.publicName.value)
  }

  @Test
  fun providerCancelledReadRemainsRetryable() = runTest {
    profiles.read = { throw CancellationException("Read cancelled") }
    openNames()
    assertEquals(PublicNameEditState(), names.publicName.value)
    profiles.read = { "Old" }
    names.loadPublicName()
    runCurrent()
    assertEquals("Old", names.publicName.value?.confirmed)
  }

  @Test
  fun providerCancelledWritePreservesConfirmedStateAndCanRetry() = runTest {
    openNames("Old")
    profiles.write = { throw CancellationException("Write cancelled") }
    names.savePublicName("New")
    runCurrent()
    assertEquals(PublicNameEditState(true, "Old", "New"), names.publicName.value)
    profiles.write = { profiles.name = it }
    names.savePublicName("New")
    runCurrent()
    assertEquals("New", names.publicName.value?.confirmed)
  }

  @Test
  fun destroyedEditorAndLateRepliesCannotAffectReplacementEditor() = runTest {
    for ((user, fails) in listOf(alice to false, bob to true)) {
      val reply = profiles.deferRead()
      val old = replaceEditor()
      names.loadPublicName()
      runCurrent()
      switchAccount(user)
      replaceEditor()
      profiles.read = { "Current" }
      names.loadPublicName()
      runCurrent()
      old.editPublicName("Stale draft")
      old.savePublicName("Stale write")
      if (fails) reply.completeExceptionally(IllegalStateException("Offline"))
      else reply.complete("Old")
      runCurrent()
      assertEquals("Current", names.publicName.value?.confirmed)
    }
  }

  @Test
  fun destroyedLoadedEditorRejectsCallbacksWithoutChangingStateOrSubmittingWrites() = runTest {
    openNames("Confirmed")
    val old = names
    val confirmed = old.publicName.value
    clearEditor()

    old.editPublicName("Stale draft")
    old.savePublicName("Stale write")
    old.savePublicName(null)
    old.loadPublicName()
    runCurrent()

    assertEquals(confirmed, old.publicName.value)
    assertEquals(listOf(alice.uid), profiles.reads)
    assertTrue(profiles.writes.isEmpty())
  }

  @Test
  fun completedWriteCleanupKeepsLaterSameUidWriteTracked() =
      verifySameUidWriteCleanup(rejected = false)

  @Test
  fun rejectedWriteCleanupKeepsLaterSameUidWriteTracked() =
      verifySameUidWriteCleanup(rejected = true)

  private fun verifySameUidWriteCleanup(rejected: Boolean) = runTest {
    val firstReply = profiles.deferWrite()
    val first = writes.submit(alice.uid, lazyOf(profiles), "First")
    runCurrent()
    val secondReply = profiles.deferWrite()
    val second = writes.submit(alice.uid, lazyOf(profiles), "Second")
    runCurrent()
    assertEquals(listOf("First"), profiles.writes)

    if (rejected) firstReply.completeExceptionally(IllegalStateException("Rejected"))
    else firstReply.complete(Unit)
    runCurrent()
    assertEquals(rejected, runCatching { first.await() }.isFailure)
    assertEquals(listOf("First", "Second"), profiles.writes)
    val reconciliation = backgroundScope.async { writes.awaitPending(alice.uid) }
    runCurrent()
    assertFalse(reconciliation.isCompleted)

    secondReply.complete(Unit)
    runCurrent()
    second.await()
    assertTrue(reconciliation.isCompleted)
    assertEquals("Second", profiles.name)
  }

  @Test
  fun identityChangeBeforeSubmissionNeverWritesForTheNewAccount() = runTest {
    openNames()
    names.savePublicName("Queued for Alice")
    switchAccount(bob)
    assertTrue(profiles.writes.isEmpty())
    assertNull(names.publicName.value)
  }

  @Test
  fun accountReturnWaitsForSubmittedWriteAndAllowsRemoval() = runTest {
    openNames()
    val reply = profiles.deferWrite()
    names.savePublicName("Alice alias")
    runCurrent()
    switchAccount(null)
    assertNull(names.publicName.value)
    switchAccount(bob)
    replaceEditor()
    names.loadPublicName()
    runCurrent()
    assertEquals(PublicNameEditState(loaded = true), names.publicName.value)
    switchAccount(alice)
    replaceEditor()
    names.loadPublicName()
    runCurrent()
    assertEquals(PublicNameEditState(busy = true), names.publicName.value)
    reply.complete(Unit)
    runCurrent()
    assertEquals("Alice alias", names.publicName.value?.confirmed)
    names.savePublicName(null)
    runCurrent()
    assertEquals(PublicNameEditState(loaded = true), names.publicName.value)
  }

  @Test
  fun closingEditorCancelsReadAndAllowsReopening() = runTest {
    val read = kotlinx.coroutines.CompletableDeferred<String?>()
    var cancelled = false
    profiles.read = {
      try {
        read.await()
      } finally {
        cancelled = true
      }
    }
    replaceEditor()
    names.loadPublicName()
    runCurrent()
    clearEditor()
    runCurrent()
    assertTrue(cancelled)
    val reopened = replaceEditor()
    assertNull(reopened.publicName.value)
    profiles.read = { "Old" }
    reopened.loadPublicName()
    runCurrent()
    assertEquals("Old", names.publicName.value?.confirmed)
  }

  @Test
  fun clearingAppOwnerCancelsCooperativeWriteWait() = runTest {
    openNames("Old")
    val write = profiles.deferWrite()
    names.savePublicName("New")
    runCurrent()
    rootStore.clear()
    runCurrent()
    write.complete(Unit)
    runCurrent()
    assertEquals("Old", profiles.name)
  }

  @Test
  fun writeCoordinatorRejectsInvalidOrDifferentOwnerWithoutSubmission() = runTest {
    for (uid in listOf("", " ", bob.uid)) {
      assertTrue(
          runCatching { writes.submit(uid, lazyOf(profiles), "New") }.exceptionOrNull()
              is IllegalStateException
      )
    }
    assertTrue(profiles.writes.isEmpty())
  }

  @Test
  fun writeCoordinatorRejectsEndedSessionBeforeObserverRuns() = runTest {
    val coordinator = writes
    publicAuth.signOut()
    assertTrue(
        runCatching { coordinator.submit(alice.uid, lazyOf(profiles), "New") }.exceptionOrNull()
            is IllegalStateException
    )
    assertTrue(profiles.writes.isEmpty())
  }

  @Test
  fun queuedWriteIsInvalidatedWhenItsOriginalSessionEnds() = runTest {
    val reply = profiles.deferWrite()
    val first = writes.submit(alice.uid, lazyOf(profiles), "Submitted")
    runCurrent()
    val queued = writes.submit(alice.uid, lazyOf(profiles), "Queued")
    runCurrent()
    switchAccount(bob)
    reply.complete(Unit)
    runCurrent()
    first.await()
    assertTrue(runCatching { queued.await() }.exceptionOrNull() is IllegalStateException)
    assertEquals(listOf("Submitted"), profiles.writes)
  }

  @Test
  fun unopenedEditorIgnoresDraftAndSaveCallbacks() = runTest {
    val model = replaceEditor()
    model.editPublicName("Draft")
    model.savePublicName("Draft")
    model.savePublicName(null)
    runCurrent()
    assertNull(model.publicName.value)
    assertTrue(profiles.reads.isEmpty())
    assertTrue(profiles.writes.isEmpty())
  }

  @Test
  fun pendingReadDeduplicatesLoadsAndRejectsEditsAndWrites() = runTest {
    val reply = profiles.deferRead()
    val model = replaceEditor()
    model.loadPublicName()
    model.loadPublicName()
    model.editPublicName("Ignored")
    model.savePublicName("Ignored")
    runCurrent()
    assertEquals(PublicNameEditState(busy = true), model.publicName.value)
    assertEquals(listOf(alice.uid), profiles.reads)
    assertTrue(profiles.writes.isEmpty())
    reply.complete(null)
    runCurrent()
    assertEquals(PublicNameEditState(loaded = true), model.publicName.value)
  }

  @Test
  fun unchangedPublicNameDoesNotSubmitAWrite() = runTest {
    openNames("Confirmed")
    names.savePublicName("Confirmed")
    runCurrent()
    assertEquals(PublicNameEditState(true, "Confirmed", "Confirmed"), names.publicName.value)
    assertTrue(profiles.writes.isEmpty())
  }

  @Test
  fun removingUnpublishedNamePreservesTheDraftWithoutWriting() = runTest {
    openNames()
    names.editPublicName("Unsaved")
    names.savePublicName(null)
    runCurrent()
    assertEquals(PublicNameEditState(loaded = true, draft = "Unsaved"), names.publicName.value)
    assertTrue(profiles.writes.isEmpty())
  }

  @Test
  fun editorWithoutValidInitialUidCannotLoad() = runTest {
    for (uid in listOf(null, " ")) {
      val model = PublicNameViewModel(lazyOf(profiles), writes, { uid }, emptyFlow())
      editorStore.put("invalid", model)
      model.loadPublicName()
      runCurrent()
      assertNull(model.publicName.value)
    }
    assertTrue(profiles.reads.isEmpty())
  }

  @Test
  fun queuedReadCannotStartAfterSnapshotSignsOut() = runTest {
    val model = replaceEditor()
    model.loadPublicName()
    publicAuth.signOut()
    runCurrent()
    assertNull(model.publicName.value)
    assertTrue(profiles.reads.isEmpty())
  }

  @Test
  fun publicSaveRequiresLoadedIdleValidChangedDraft() {
    val changed = PublicNameEditState(true, "Old", "New")
    assertTrue(changed.canSave)
    for (state in
        listOf(
            changed.copy(loaded = false),
            changed.copy(busy = true),
            changed.copy(draft = " "),
            changed.copy(draft = "Old"),
        )) {
      assertFalse(state.canSave)
    }
  }
}
