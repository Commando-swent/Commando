package com.android.sample.ui.profile

import androidx.lifecycle.ViewModelStore
import com.android.sample.data.repository.ProfilePhotoRepository
import com.android.sample.model.ProfilePhotoSource
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.model.authentication.ProfileUpdateResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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

  @Test
  fun editValidationCancellationAndReopeningPreserveConfirmedValues() = runTest {
    val fake = FakeAuthRepository(alice)
    val model = createViewModel(fake)
    runCurrent()
    model.beginEditing()
    assertEquals(alice.displayName, model.editState.value!!.fullName)
    assertEquals(alice.email, model.editState.value!!.email)
    model.updateFullName(" ")
    model.updateEmail("invalid")
    model.saveProfile()
    runCurrent()
    assertFalse(model.editState.value!!.canSave)
    assertEquals(alice, fake.currentUser)
    model.cancelEditing()
    assertNull(model.editState.value)
    model.beginEditing()
    model.updateFullName(" Alice Dupont ")
    model.updateEmail(" new@epfl.ch ")
    model.saveProfile()
    runCurrent()
    assertTrue(model.editState.value!!.saved)
    assertEquals("new@epfl.ch", model.editState.value!!.pendingEmail)
    assertEquals(ProfileUiState.Content("Alice Dupont", alice.email), model.uiState.value)
  }

  @Test
  fun saveFailuresKeepDraftAndCorrectionAllowsRetry() = runTest {
    val fake = FakeAuthRepository(alice)
    val model = createViewModel(fake)
    runCurrent()
    model.beginEditing()
    for (error in
        listOf(
            AuthException.Network(),
            AuthException.RequiresRecentLogin(),
            AuthException.EmailAlreadyInUse(),
            AuthException.InvalidEmail(),
            AuthException.Unknown(),
        )) {
      fake.updateProfileResult = Result.failure(error)
      model.updateEmail(alice.email!!)
      model.updateFullName("Draft name")
      model.saveProfile()
      runCurrent()
      assertSame(error, model.editState.value!!.error)
      assertEquals("Draft name", model.editState.value!!.fullName)
      assertEquals(alice, fake.currentUser)
    }
    fake.updateProfileResult = null
    model.updateEmail(alice.email!!)
    model.saveProfile()
    runCurrent()
    assertTrue(model.editState.value!!.saved)
    assertNull(model.editState.value!!.pendingEmail)
  }

  @Test
  fun savingGuardsDuplicateClicksAndAccountChangeCancelsTheOldDraft() = runTest {
    val fake = FakeAuthRepository(alice)
    val pending = CompletableDeferred<Result<ProfileUpdateResult>>()
    var calls = 0
    val repository =
        object : AuthRepository by fake {
          override suspend fun updateProfile(
              fullName: String,
              email: String,
          ): Result<ProfileUpdateResult> {
            calls++
            return pending.await()
          }
        }
    val model = createViewModel(repository)
    runCurrent()
    model.beginEditing()
    model.saveProfile()
    model.saveProfile()
    model.cancelEditing()
    model.updateFullName("Ignored")
    runCurrent()
    assertEquals(1, calls)
    assertEquals(alice.displayName, model.editState.value!!.fullName)
    fake.signInWithEmailResult = Result.success(AuthUser("bob", "Bob", "bob@epfl.ch"))
    fake.signInWithEmail("", "")
    runCurrent()
    pending.complete(Result.success(ProfileUpdateResult(alice)))
    runCurrent()
    assertNull(model.editState.value)
    assertEquals(ProfileUiState.Content("Bob", "bob@epfl.ch"), model.uiState.value)
  }

  @Test
  fun photoFailuresAndStaleSelectionsPreserveTheAccount() = runTest {
    val fake = FakeAuthRepository(alice)
    val bytes = byteArrayOf(1, 2, 3)
    val image = java.util.Base64.getEncoder().encodeToString(bytes)
    var fail = false
    var pending = CompletableDeferred<ByteArray?>(bytes)
    val photos =
        object : ProfilePhotoRepository {
          override suspend fun load(userId: String): ByteArray? = null

          override suspend fun save(userId: String, source: ProfilePhotoSource?): ByteArray? {
            if (fail) error("Offline")
            return pending.await()
          }
        }
    val model = ProfileViewModel(fake, photos).also { store.put("photo", it) }
    runCurrent()
    assertNull(model.photoState.value.image)
    val source = ProfilePhotoSource.Gallery(io.mockk.mockk())
    model.savePhoto(alice.uid, source)
    runCurrent()
    assertEquals(image, model.photoState.value.image)
    fail = true
    for (selection in listOf(null, source)) {
      model.savePhoto(alice.uid, selection)
      runCurrent()
      assertEquals(PhotoUiState(image, failed = true), model.photoState.value)
    }
    fail = false
    fake.signInWithEmailResult = Result.success(AuthUser("bob"))
    fake.signInWithEmail("", "")
    runCurrent()
    model.savePhoto(alice.uid, source)
    assertEquals(PhotoUiState(), model.photoState.value)
    pending = CompletableDeferred()
    model.savePhoto("bob", source)
    model.savePhoto("bob", source)
    runCurrent()
    assertTrue(model.photoState.value.busy)
    fake.signOut()
    runCurrent()
    model.savePhoto(alice.uid, source)
    pending.complete(bytes)
    runCurrent()
    assertEquals(PhotoUiState(), model.photoState.value)
    assertNull(model.photoOwner())
    model.refreshPhoto()
    assertEquals(PhotoUiState(), model.photoState.value)
  }

  @Test
  fun previousAccountsLatePhotoCannotReplaceTheCurrentAvatar() = verifyLatePhotoLoad(fails = false)

  @Test
  fun previousAccountsLateFailureCannotShowAnErrorForTheCurrentAccount() =
      verifyLatePhotoLoad(fails = true)

  private fun verifyLatePhotoLoad(fails: Boolean) = runTest {
    val auth = FakeAuthRepository(alice)
    val previousLoad = CompletableDeferred<ByteArray?>()
    val currentPhoto = byteArrayOf(4, 5, 6)
    val photos =
        object : ProfilePhotoRepository {
          override suspend fun load(userId: String): ByteArray? =
              if (userId == alice.uid) withContext(NonCancellable) { previousLoad.await() }
              else currentPhoto

          override suspend fun save(userId: String, source: ProfilePhotoSource?): ByteArray? = null
        }
    val model = ProfileViewModel(auth, photos).also { store.put("photo", it) }
    runCurrent()
    auth.signInWithEmailResult = Result.success(AuthUser("bob"))
    auth.signInWithEmail("", "")
    runCurrent()
    val confirmed = PhotoUiState(java.util.Base64.getEncoder().encodeToString(currentPhoto))
    assertEquals(confirmed, model.photoState.value)

    if (fails) previousLoad.completeExceptionally(IllegalStateException("Offline"))
    else previousLoad.complete(byteArrayOf(1, 2, 3))
    runCurrent()

    assertEquals(confirmed, model.photoState.value)
    assertEquals("bob", model.photoOwner())
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
