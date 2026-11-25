package com.commando.app.ui.session

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModelStore
import com.android.sample.MainDispatcherRule
import com.commando.app.data.repository.PublicProfileTestRepository
import com.commando.app.model.authentication.AuthException
import com.commando.app.model.authentication.AuthRepository
import com.commando.app.model.authentication.AuthUser
import com.commando.app.model.authentication.FakeAuthRepository
import com.commando.app.ui.profile.PublicNameEditState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
  private val alice = AuthUser("alice", email = "alice@epfl.ch")
  private val bob = AuthUser("bob", email = "bob@epfl.ch")
  private val store = ViewModelStore()

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @After
  fun tearDown() {
    store.clear()
  }

  private fun createViewModel(repository: AuthRepository): SessionViewModel {
    return SessionViewModel(repository).also { store.put("session", it) }
  }

  private val publicAuth = FakeAuthRepository(alice.copy(displayName = "Private name"))
  private val profiles = PublicProfileTestRepository()
  private val names by lazy { createViewModel(publicAuth) }

  private fun TestScope.openNames(initial: String? = null) {
    profiles.name = initial
    reopenNames()
  }

  private fun TestScope.reopenNames() {
    names.closePublicNameEditor()
    names.beginPublicNameEditor("edit")
    names.loadPublicName(lazyOf(profiles))
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
    names.loadPublicName(lazyOf(profiles))
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
    names.loadPublicName(lazyOf(profiles))
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
    names.loadPublicName(lazyOf(profiles))
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
  fun oldDisposalAndLateRepliesCannotAffectReplacementEditor() = runTest {
    for ((user, fails) in listOf(alice to false, bob to true)) {
      val reply = profiles.deferRead()
      names.beginPublicNameEditor("edit")
      names.loadPublicName(lazyOf(profiles))
      runCurrent()
      switchAccount(user)
      names.beginPublicNameEditor("replacement")
      profiles.read = { "Current" }
      names.loadPublicName(lazyOf(profiles))
      runCurrent()
      names.closePublicNameEditor("edit")
      names.editPublicName("Stale draft", "edit")
      names.savePublicName("Stale write", "edit")
      if (fails) reply.completeExceptionally(IllegalStateException("Offline"))
      else reply.complete("Old")
      runCurrent()
      assertEquals("Current", names.publicName.value?.confirmed)
    }
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
    names.beginPublicNameEditor("bob")
    names.loadPublicName(lazyOf(profiles))
    runCurrent()
    assertEquals(PublicNameEditState(loaded = true), names.publicName.value)
    switchAccount(alice)
    names.beginPublicNameEditor("alice-return")
    names.loadPublicName(lazyOf(profiles))
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
    names.beginPublicNameEditor("edit")
    names.loadPublicName(lazyOf(profiles))
    runCurrent()
    names.closePublicNameEditor()
    runCurrent()
    assertTrue(cancelled)
    assertNull(names.publicName.value)
    profiles.read = { "Old" }
    names.beginPublicNameEditor("replacement")
    names.loadPublicName(lazyOf(profiles))
    runCurrent()
    assertEquals("Old", names.publicName.value?.confirmed)
  }

  @Test
  fun clearingAppOwnerCancelsCooperativeWriteWait() = runTest {
    openNames("Old")
    val write = profiles.deferWrite()
    names.savePublicName("New")
    runCurrent()
    store.clear()
    runCurrent()
    write.complete(Unit)
    runCurrent()
    assertEquals("Old", profiles.name)
  }

  @Test
  fun signedOutSessionIsAvailableImmediately() = runTest {
    val viewModel = createViewModel(FakeAuthRepository())
    assertEquals(SessionUiState(), viewModel.uiState.value)
    runCurrent()
    assertEquals(SessionUiState(), viewModel.uiState.value)
  }

  @Test
  fun existingSessionIsAvailableBeforeCollectionStarts() = runTest {
    val viewModel = createViewModel(FakeAuthRepository(alice))
    assertEquals(alice, viewModel.uiState.value.user)
    runCurrent()
    assertEquals(alice, viewModel.uiState.value.user)
  }

  @Test
  fun observesSignInAccountChangesAndExternalSignOut() = runTest {
    val repository = FakeAuthRepository()
    val viewModel = createViewModel(repository)
    runCurrent()

    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    runCurrent()
    assertEquals(alice, viewModel.uiState.value.user)

    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    runCurrent()
    assertEquals(bob, viewModel.uiState.value.user)

    repository.signOut()
    runCurrent()
    assertEquals(SessionUiState(), viewModel.uiState.value)
  }

  @Test
  fun successfulSignOutClearsTheRepositoryAndObservedSession() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = createViewModel(repository)
    runCurrent()
    assertTrue(viewModel.signOut())
    runCurrent()
    assertNull(repository.currentUser)
    assertEquals(SessionUiState(), viewModel.uiState.value)
  }

  @Test
  fun failedSignOutPreservesSessionAndRetryCanSucceed() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = createViewModel(repository)
    val error = AuthException.Network()
    repository.signOutResult = Result.failure(error)
    assertFalse(viewModel.signOut())
    runCurrent()
    assertEquals(alice, repository.currentUser)
    assertEquals(alice, viewModel.uiState.value.user)
    assertSame(error, viewModel.uiState.value.signOutError)

    repository.signOutResult = Result.success(Unit)
    assertTrue(viewModel.signOut())
    runCurrent()
    assertEquals(SessionUiState(), viewModel.uiState.value)
  }

  @Test
  fun errorCanBeDismissedAndDoesNotCarryIntoAnotherAccount() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = createViewModel(repository)
    runCurrent()
    repository.signOutResult = Result.failure(AuthException.Network())
    viewModel.signOut()
    viewModel.clearSignOutError()
    assertEquals(SessionUiState(user = alice), viewModel.uiState.value)

    viewModel.signOut()
    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    runCurrent()
    assertEquals(SessionUiState(user = bob), viewModel.uiState.value)
  }

  @Test
  fun thrownSignOutFailureIsReported() = runTest {
    val fake = FakeAuthRepository(alice)
    val failure = IllegalStateException("Provider failure")
    val repository =
        object : AuthRepository by fake {
          override fun signOut(): Result<Unit> = throw failure
        }
    val viewModel = createViewModel(repository)
    assertFalse(viewModel.signOut())
    assertEquals(alice, viewModel.uiState.value.user)
    assertSame(failure, viewModel.uiState.value.signOutError?.cause)
  }

  @Test
  fun signOutCancellationIsRethrown() = runTest {
    val fake = FakeAuthRepository(alice)
    val cancelledRepository =
        object : AuthRepository by fake {
          override fun signOut(): Result<Unit> = throw CancellationException()
        }
    val cancelledViewModel = createViewModel(cancelledRepository)
    assertThrows(CancellationException::class.java) { cancelledViewModel.signOut() }
    assertNull(cancelledViewModel.uiState.value.signOutError)
  }

  @Test
  fun clearingViewModelStopsSessionObservation() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = createViewModel(repository)
    runCurrent()
    store.clear()
    repository.signOut()
    runCurrent()
    assertEquals(alice, viewModel.uiState.value.user)
  }
}
