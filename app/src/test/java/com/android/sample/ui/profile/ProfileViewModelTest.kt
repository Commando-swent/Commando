package com.android.sample.ui.profile

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModelStore
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
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
class ProfileViewModelTest {
  private val alice = AuthUser("alice", displayName = "Alice Martin", email = "alice@epfl.ch")
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

  private fun createViewModel(repository: AuthRepository): ProfileViewModel =
      ProfileViewModel(repository).also { store.put("profile", it) }

  @Test
  fun startsLoadingBeforeReceivingExistingUser() = runTest {
    val viewModel = createViewModel(FakeAuthRepository(alice))

    assertEquals(ProfileUiState.Loading, viewModel.uiState.value)
    runCurrent()

    assertEquals(
        ProfileUiState.Content("Alice Martin", "alice@epfl.ch"),
        viewModel.uiState.value,
    )
  }

  @Test
  fun remainsLoadingUntilAuthFlowEmitsInsteadOfUsingSnapshot() = runTest {
    val repository = ControlledAuthRepository()
    val viewModel = createViewModel(repository)
    runCurrent()

    assertEquals(ProfileUiState.Loading, viewModel.uiState.value)
    repository.emit(alice)
    runCurrent()

    assertEquals(
        ProfileUiState.Content("Alice Martin", "alice@epfl.ch"),
        viewModel.uiState.value,
    )
  }

  @Test
  fun absentUserBecomesMissingUserWithoutProfileInformation() = runTest {
    val viewModel = createViewModel(FakeAuthRepository())
    runCurrent()

    assertEquals(ProfileUiState.MissingUser, viewModel.uiState.value)
  }

  @Test
  fun delayedNullEmissionEndsLoadingWithMissingUser() = runTest {
    val repository = ControlledAuthRepository()
    val viewModel = createViewModel(repository)
    runCurrent()

    repository.emit(null)
    runCurrent()

    assertEquals(ProfileUiState.MissingUser, viewModel.uiState.value)
  }

  @Test
  fun nullEmptyAndWhitespaceNamesRemainAbsentWithoutPlaceholderData() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = createViewModel(repository)
    runCurrent()

    for (name in listOf(null, "", " ", "\t\n ")) {
      repository.signInWithEmailResult = Result.success(alice.copy(displayName = name))
      repository.signInWithEmail("", "")
      runCurrent()

      assertEquals(ProfileUiState.Content(null, alice.email), viewModel.uiState.value)
    }
  }

  @Test
  fun preservesActualNameSpellingAndEmail() = runTest {
    val user = alice.copy(displayName = "  Élodie van der Meer  ", email = "Elodie+work@epfl.ch")
    val viewModel = createViewModel(FakeAuthRepository(user))
    runCurrent()

    assertEquals(ProfileUiState.Content(user.displayName, user.email), viewModel.uiState.value)
  }

  @Test
  fun missingEmailIsNotReplacedWithFabricatedInformation() = runTest {
    val viewModel = createViewModel(FakeAuthRepository(alice.copy(email = null)))
    runCurrent()

    assertEquals(ProfileUiState.Content("Alice Martin", null), viewModel.uiState.value)
  }

  @Test
  fun receivesProfileAfterSignUp() = runTest {
    val repository = FakeAuthRepository()
    val viewModel = createViewModel(repository)
    runCurrent()
    repository.signUpWithEmailResult = Result.success(alice)

    repository.signUpWithEmail(alice.email!!, "password")
    runCurrent()

    assertEquals(
        ProfileUiState.Content("Alice Martin", "alice@epfl.ch"),
        viewModel.uiState.value,
    )
  }

  @Test
  fun accountChangeReplacesAllPreviousInformationEvenWhenNewFieldsAreAbsent() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = createViewModel(repository)
    runCurrent()
    repository.signInWithEmailResult = Result.success(AuthUser("bob"))

    repository.signInWithEmail("", "")
    runCurrent()

    assertEquals(ProfileUiState.Content(null, null), viewModel.uiState.value)
  }

  @Test
  fun changedDetailsForSameAccountAreObserved() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = createViewModel(repository)
    runCurrent()
    val updated = alice.copy(displayName = "Alice Dupont", email = "alice.dupont@epfl.ch")
    repository.signInWithEmailResult = Result.success(updated)

    repository.signInWithEmail("", "")
    runCurrent()

    assertEquals(
        ProfileUiState.Content(updated.displayName, updated.email),
        viewModel.uiState.value,
    )
  }

  @Test
  fun signOutClearsProfileAndLaterSignInLoadsTheNewAccount() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = createViewModel(repository)
    runCurrent()

    repository.signOut()
    runCurrent()
    assertEquals(ProfileUiState.MissingUser, viewModel.uiState.value)

    val bob = AuthUser("bob", displayName = "Bob Lee", email = "bob@epfl.ch")
    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    runCurrent()
    assertEquals(ProfileUiState.Content("Bob Lee", "bob@epfl.ch"), viewModel.uiState.value)
  }

  @Test
  fun clearingViewModelCancelsAuthObservation() = runTest {
    val repository = ControlledAuthRepository()
    val viewModel = createViewModel(repository)
    runCurrent()
    repository.emit(alice)
    runCurrent()
    assertTrue(repository.isCollecting)

    store.clear()
    runCurrent()
    assertFalse(repository.isCollecting)

    repository.emit(null)
    runCurrent()
    assertEquals(
        ProfileUiState.Content("Alice Martin", "alice@epfl.ch"),
        viewModel.uiState.value,
    )
  }

  private class ControlledAuthRepository : AuthRepository by FakeAuthRepository() {
    private val users = MutableSharedFlow<AuthUser?>(replay = 1)
    var isCollecting = false
      private set

    override val currentUser: AuthUser?
      get() = error("Profile must obtain users from observeAuthState, not a snapshot")

    override fun observeAuthState(): Flow<AuthUser?> = flow {
      isCollecting = true
      try {
        users.collect { emit(it) }
      } finally {
        isCollecting = false
      }
    }

    suspend fun emit(user: AuthUser?) {
      users.emit(user)
    }
  }
}
