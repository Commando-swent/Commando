package com.android.sample.ui.session

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModelStore
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
  private val alice = AuthUser("alice", email = "alice@epfl.ch")
  private val bob = AuthUser("bob", email = "bob@epfl.ch")
  private val store = ViewModelStore()

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    store.clear()
    Dispatchers.resetMain()
  }

  private fun createViewModel(repository: AuthRepository): SessionViewModel {
    return SessionViewModel(repository).also { store.put("session", it) }
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
