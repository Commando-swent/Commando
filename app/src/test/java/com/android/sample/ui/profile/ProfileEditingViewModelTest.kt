package com.android.sample.ui.profile

import androidx.lifecycle.ViewModelStore
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.model.authentication.ProfileUpdateResult
import kotlinx.coroutines.CompletableDeferred
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
class ProfileEditingViewModelTest {
  private val alice = AuthUser("alice", "Alice Martin", "alice@example.test")
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

  private fun model(repository: AuthRepository): ProfileViewModel =
      ProfileViewModel(repository).also { store.put("profile", it) }

  @Test
  fun editingStartsFromConfirmedUserAndCancelDiscardsDraft() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = model(repository)
    runCurrent()
    viewModel.beginEditing()
    assertEquals(alice.displayName, viewModel.editState.value?.fullName)
    assertEquals(alice.email, viewModel.editState.value?.email)
    viewModel.updateFullName("Draft")
    viewModel.cancelEditing()
    assertNull(viewModel.editState.value)
    assertEquals(alice, repository.currentUser)
    viewModel.beginEditing()
    assertEquals(alice.displayName, viewModel.editState.value?.fullName)
  }

  @Test
  fun missingUserCannotStartEditingOrSaving() = runTest {
    val viewModel = model(FakeAuthRepository())
    runCurrent()
    viewModel.beginEditing()
    viewModel.updateFullName("Alice")
    viewModel.updateEmail("alice@example.test")
    viewModel.saveProfile()
    assertNull(viewModel.editState.value)
  }

  @Test
  fun absentProfileFieldsBecomeEmptyInvalidDraftNotInventedValues() = runTest {
    val viewModel = model(FakeAuthRepository(AuthUser("alice")))
    runCurrent()
    viewModel.beginEditing()
    val draft = viewModel.editState.value!!
    assertEquals("", draft.fullName)
    assertEquals("", draft.email)
    assertTrue(draft.fullNameError)
    assertTrue(draft.emailError)
    assertFalse(draft.canSave)
  }

  @Test
  fun emptyOrInvalidDraftBlocksSaveAndClearsErrorsWhenCorrected() = runTest {
    val repository = RecordingRepository(FakeAuthRepository(alice))
    val viewModel = model(repository)
    runCurrent()
    viewModel.beginEditing()
    viewModel.updateFullName(" \n ")
    viewModel.updateEmail("bad")
    viewModel.saveProfile()
    runCurrent()
    assertEquals(0, repository.calls)
    assertTrue(viewModel.editState.value!!.fullNameError)
    assertTrue(viewModel.editState.value!!.emailError)
    assertFalse(viewModel.editState.value!!.canSave)
    viewModel.updateFullName("Alice Dupont")
    viewModel.updateEmail("alice@example.test")
    assertFalse(viewModel.editState.value!!.fullNameError)
    assertFalse(viewModel.editState.value!!.emailError)
    assertTrue(viewModel.editState.value!!.canSave)
  }

  @Test
  fun successNormalizesValuesAndUpdatesConfirmedNameOnly() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = model(repository)
    runCurrent()
    viewModel.beginEditing()
    viewModel.updateFullName(" Alice Dupont ")
    viewModel.updateEmail(" new@example.test ")
    viewModel.saveProfile()
    assertTrue(viewModel.editState.value!!.isSaving)
    runCurrent()
    assertEquals(
        ProfileUiState.Content("Alice Dupont", "alice@example.test"),
        viewModel.uiState.value,
    )
    val draft = viewModel.editState.value!!
    assertTrue(draft.saved)
    assertFalse(draft.isSaving)
    assertFalse(draft.canSave)
    assertEquals("new@example.test", draft.pendingEmail)
  }

  @Test
  fun saveWithoutEmailChangeHasNoPendingEmail() = runTest {
    val viewModel = model(FakeAuthRepository(alice))
    runCurrent()
    viewModel.beginEditing()
    viewModel.updateFullName("Alice Dupont")
    viewModel.saveProfile()
    runCurrent()
    assertTrue(viewModel.editState.value!!.saved)
    assertNull(viewModel.editState.value!!.pendingEmail)
  }

  @Test
  fun duplicateSaveFieldChangesCancelAndReentryAreIgnoredDuringSaving() = runTest {
    val repository = RecordingRepository(FakeAuthRepository(alice))
    repository.pending = CompletableDeferred()
    val viewModel = model(repository)
    runCurrent()
    viewModel.beginEditing()
    viewModel.updateFullName("Alice Dupont")
    viewModel.saveProfile()
    viewModel.saveProfile()
    viewModel.updateFullName("Ignored")
    viewModel.updateEmail("ignored@example.test")
    viewModel.cancelEditing()
    viewModel.beginEditing()
    runCurrent()
    assertEquals(1, repository.calls)
    assertEquals("Alice Dupont", viewModel.editState.value!!.fullName)
    assertEquals(alice.email, viewModel.editState.value!!.email)
    repository.pending!!.complete(
        Result.success(ProfileUpdateResult(alice.copy(displayName = "Alice Dupont")))
    )
    runCurrent()
    viewModel.saveProfile()
    runCurrent()
    assertEquals(1, repository.calls)
  }

  @Test
  fun failureKeepsDraftConfirmedUserAndAllowsRetry() = runTest {
    val repository = FakeAuthRepository(alice)
    repository.updateProfileResult = Result.failure(AuthException.Network())
    val viewModel = model(repository)
    runCurrent()
    viewModel.beginEditing()
    viewModel.updateFullName("Alice Dupont")
    viewModel.saveProfile()
    runCurrent()
    val draft = viewModel.editState.value!!
    assertEquals("Alice Dupont", draft.fullName)
    assertEquals(ProfileSaveError.NETWORK, draft.error)
    assertFalse(draft.isSaving)
    assertTrue(draft.canSave)
    assertEquals(ProfileUiState.Content(alice.displayName, alice.email), viewModel.uiState.value)
    repository.updateProfileResult = null
    viewModel.saveProfile()
    runCurrent()
    assertTrue(viewModel.editState.value!!.saved)
    assertNull(viewModel.editState.value!!.error)
  }

  @Test
  fun publicErrorsAreCategorizedWithoutProviderMessages() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = model(repository)
    runCurrent()
    viewModel.beginEditing()
    for ((error, expected) in
        listOf(
            AuthException.EmailAlreadyInUse() to ProfileSaveError.EMAIL_IN_USE,
            AuthException.AccountConflict() to ProfileSaveError.EMAIL_IN_USE,
            AuthException.RequiresRecentLogin() to ProfileSaveError.RECENT_LOGIN_REQUIRED,
            AuthException.SessionChanged() to ProfileSaveError.SESSION_CHANGED,
            AuthException.Unknown(IllegalStateException("private SDK data")) to
                ProfileSaveError.UNKNOWN,
        )) {
      repository.updateProfileResult = Result.failure(error)
      viewModel.saveProfile()
      runCurrent()
      assertEquals(expected, viewModel.editState.value!!.error)
    }
  }

  @Test
  fun providerInvalidEmailShowsFieldErrorAndTypingClearsFailure() = runTest {
    val repository = FakeAuthRepository(alice)
    repository.updateProfileResult = Result.failure(AuthException.InvalidEmail())
    val viewModel = model(repository)
    runCurrent()
    viewModel.beginEditing()
    viewModel.saveProfile()
    runCurrent()
    assertTrue(viewModel.editState.value!!.emailError)
    assertFalse(viewModel.editState.value!!.canSave)
    viewModel.updateEmail("corrected@example.test")
    assertFalse(viewModel.editState.value!!.emailError)
    assertNull(viewModel.editState.value!!.error)
  }

  @Test
  fun signOutAndAccountChangeClearDraftAndCancelPendingSave() = runTest {
    val fake = FakeAuthRepository(alice)
    val repository = RecordingRepository(fake)
    repository.pending = CompletableDeferred()
    val viewModel = model(repository)
    runCurrent()
    viewModel.beginEditing()
    viewModel.saveProfile()
    runCurrent()
    fake.signOut()
    runCurrent()
    assertNull(viewModel.editState.value)
    assertEquals(ProfileUiState.MissingUser, viewModel.uiState.value)
    val bob = AuthUser("bob", "Bob Lee", "bob@example.test")
    fake.signInWithEmailResult = Result.success(bob)
    fake.signInWithEmail("", "")
    runCurrent()
    repository.pending!!.complete(Result.success(ProfileUpdateResult(alice)))
    runCurrent()
    assertEquals(ProfileUiState.Content(bob.displayName, bob.email), viewModel.uiState.value)
    viewModel.beginEditing()
    assertEquals(bob.displayName, viewModel.editState.value!!.fullName)
  }

  @Test
  fun wrongAccountInUpdateResultCannotPopulateProfileOrDraft() = runTest {
    val repository = RecordingRepository(FakeAuthRepository(alice))
    repository.pending = CompletableDeferred()
    val viewModel = model(repository)
    runCurrent()
    viewModel.beginEditing()
    viewModel.saveProfile()
    runCurrent()
    repository.pending!!.complete(Result.success(ProfileUpdateResult(AuthUser("bob", "Bob"))))
    runCurrent()
    assertNull(viewModel.editState.value)
    assertEquals(ProfileUiState.Content(alice.displayName, alice.email), viewModel.uiState.value)
  }

  @Test
  fun noDraftActionsDoNothingAndSavedDraftCanBeEditedAgain() = runTest {
    val repository = RecordingRepository(FakeAuthRepository(alice))
    val viewModel = model(repository)
    runCurrent()
    viewModel.updateFullName("Ignored")
    viewModel.updateEmail("ignored@example.test")
    viewModel.saveProfile()
    viewModel.cancelEditing()
    assertNull(viewModel.editState.value)
    assertEquals(0, repository.calls)
    viewModel.beginEditing()
    viewModel.saveProfile()
    runCurrent()
    assertTrue(viewModel.editState.value!!.saved)
    viewModel.updateFullName("Alice Dupont")
    assertFalse(viewModel.editState.value!!.saved)
    assertTrue(viewModel.editState.value!!.canSave)
    viewModel.saveProfile()
    runCurrent()
    viewModel.updateEmail("new@example.test")
    assertFalse(viewModel.editState.value!!.saved)
    assertTrue(viewModel.editState.value!!.canSave)
  }

  @Test
  fun sameAccountUpdatesConfirmedProfileWhileRetainingUnsavedDraft() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = model(repository)
    runCurrent()
    viewModel.beginEditing()
    viewModel.updateFullName("Unsaved draft")
    repository.signInWithEmailResult = Result.success(alice.copy(displayName = "External change"))
    repository.signInWithEmail("", "")
    runCurrent()
    assertEquals("Unsaved draft", viewModel.editState.value!!.fullName)
    assertEquals(ProfileUiState.Content("External change", alice.email), viewModel.uiState.value)
  }

  private class RecordingRepository(private val fake: FakeAuthRepository) : AuthRepository by fake {
    var calls = 0
    var pending: CompletableDeferred<Result<ProfileUpdateResult>>? = null

    override suspend fun updateProfile(
        fullName: String,
        email: String,
    ): Result<ProfileUpdateResult> {
      calls++
      return pending?.await() ?: fake.updateProfile(fullName, email)
    }
  }
}
