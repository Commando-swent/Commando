package com.android.sample.ui.auth

import android.os.Bundle
import androidx.credentials.Credential
import androidx.credentials.CustomCredential
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthViewModelTest {
  private val user = AuthUser("alex", email = "alex@epfl.ch")
  private val fake = FakeAuthRepository()
  private val repository = RecordingRepository(fake)
  private val viewModel = AuthViewModel(repository)

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun fillForm(mode: AuthMode = AuthMode.LOGIN) {
    viewModel.switchMode(mode)
    viewModel.updateEmail("alex@epfl.ch")
    viewModel.updatePassword("Campus2026!")
    viewModel.updateConfirmation("Campus2026!")
  }

  @Test
  fun blankFieldsAreRequiredAndNeverSubmitted() = runTest {
    viewModel.switchMode(AuthMode.SIGN_UP)
    viewModel.updateEmail("  ")
    viewModel.updatePassword("  ")
    viewModel.submitEmail()
    advanceUntilIdle()
    with(viewModel.uiState.value) {
      assertEquals(FieldError.REQUIRED, emailError)
      assertEquals(FieldError.REQUIRED, passwordError)
      assertEquals(FieldError.REQUIRED, confirmationError)
      assertFalse(isLoading)
    }
    assertEquals(0, repository.calls)
  }

  @Test
  fun malformedEmailsAreRejected() = runTest {
    fillForm()
    for (email in listOf("alex", "alex@", "@epfl.ch", "alex epfl@epfl.ch")) {
      viewModel.updateEmail(email)
      viewModel.submitEmail()
      assertEquals(FieldError.INVALID_EMAIL, viewModel.uiState.value.emailError)
    }
    advanceUntilIdle()
    assertEquals(0, repository.calls)
  }

  @Test
  fun mismatchedConfirmationBlocksSignUp() = runTest {
    fillForm(AuthMode.SIGN_UP)
    viewModel.updateConfirmation("different")
    viewModel.submitEmail()
    advanceUntilIdle()
    assertEquals(FieldError.PASSWORD_MISMATCH, viewModel.uiState.value.confirmationError)
    assertEquals(0, repository.calls)
  }

  @Test
  fun loginDoesNotRequireConfirmationAndPreservesPasswordExactly() = runTest {
    fake.signInWithEmailResult = Result.success(user)
    fillForm()
    viewModel.updateEmail(" alex@epfl.ch ")
    viewModel.updatePassword(" password with spaces ")
    viewModel.updateConfirmation("")
    viewModel.submitEmail()
    advanceUntilIdle()
    assertEquals("alex@epfl.ch", repository.email)
    assertEquals(" password with spaces ", repository.password)
    assertEquals(user, viewModel.uiState.value.user)
    assertEquals(user, fake.currentUser)
  }

  @Test
  fun signUpUsesRepositoryAndClearsPasswordsAfterSuccess() = runTest {
    fake.signUpWithEmailResult = Result.success(user)
    fillForm(AuthMode.SIGN_UP)
    viewModel.submitEmail()
    advanceUntilIdle()
    assertEquals(AuthMode.SIGN_UP, repository.mode)
    assertEquals(user, viewModel.uiState.value.user)
    assertEquals("", viewModel.uiState.value.password)
    assertEquals("", viewModel.uiState.value.confirmation)
  }

  @Test
  fun loadingStartsBeforeCoroutineAndPreventsRepeatedSubmissions() = runTest {
    fillForm()
    repository.gate = CompletableDeferred()
    viewModel.submitEmail()
    assertTrue(viewModel.uiState.value.isLoading)
    viewModel.submitEmail()
    viewModel.signInWithGoogle { error("Picker must not open while busy") }
    runCurrent()
    assertEquals(1, repository.calls)
    repository.gate!!.complete(Unit)
    advanceUntilIdle()
    assertFalse(viewModel.uiState.value.isLoading)
  }

  @Test
  fun formAndModeCannotChangeDuringSubmission() = runTest {
    fillForm()
    repository.gate = CompletableDeferred()
    viewModel.submitEmail()
    val submitting = viewModel.uiState.value
    viewModel.switchMode(AuthMode.SIGN_UP)
    viewModel.updateEmail("other@epfl.ch")
    viewModel.updatePassword("other")
    viewModel.updateConfirmation("other")
    assertEquals(submitting, viewModel.uiState.value)
    repository.gate!!.complete(Unit)
    advanceUntilIdle()
  }

  @Test
  fun authenticationErrorIsReportedAndRetryCanSucceed() = runTest {
    fake.signInWithEmailResult = Result.failure(AuthException.Network())
    fillForm()
    viewModel.submitEmail()
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.authError is AuthException.Network)
    assertNull(viewModel.uiState.value.user)
    assertFalse(viewModel.uiState.value.isLoading)
    fake.signInWithEmailResult = Result.success(user)
    viewModel.submitEmail()
    advanceUntilIdle()
    assertNull(viewModel.uiState.value.authError)
    assertEquals(user, viewModel.uiState.value.user)
  }

  @Test
  fun switchingModeKeepsEmailButClearsPasswordsAndErrors() = runTest {
    fillForm(AuthMode.SIGN_UP)
    viewModel.updateConfirmation("different")
    viewModel.submitEmail()
    viewModel.switchMode(AuthMode.LOGIN)
    assertEquals(AuthUiState(email = "alex@epfl.ch"), viewModel.uiState.value)
  }

  @Test
  fun googleUsesPickerCredentialWithoutEmailValidation() = runTest {
    fake.signInWithGoogleResult = Result.success(user)
    val credential = CustomCredential("test.google", Bundle())
    viewModel.signInWithGoogle { credential }
    advanceUntilIdle()
    assertSame(credential, repository.credential)
    assertEquals(user, viewModel.uiState.value.user)
    assertNull(viewModel.uiState.value.emailError)
  }

  @Test
  fun googlePickerLoadingPreventsRepeatedGoogleAndEmailSubmissions() = runTest {
    val picker = CompletableDeferred<Credential?>()
    var pickerCalls = 0
    viewModel.signInWithGoogle {
      pickerCalls++
      picker.await()
    }
    assertTrue(viewModel.uiState.value.isLoading)
    runCurrent()
    viewModel.signInWithGoogle {
      pickerCalls++
      null
    }
    viewModel.submitEmail()
    runCurrent()
    assertEquals(1, pickerCalls)
    assertEquals(0, repository.calls)
    picker.complete(null)
    advanceUntilIdle()
    assertEquals(AuthUiState(), viewModel.uiState.value)
  }

  @Test
  fun dismissingGooglePickerIsNotAnAuthenticationError() = runTest {
    viewModel.signInWithGoogle { null }
    advanceUntilIdle()
    assertEquals(0, repository.calls)
    assertEquals(AuthUiState(), viewModel.uiState.value)
  }

  @Test
  fun cancelledGoogleRequestDoesNotBecomeAnAuthenticationError() = runTest {
    viewModel.signInWithGoogle { throw CancellationException() }
    advanceUntilIdle()
    assertEquals(AuthUiState(), viewModel.uiState.value)
  }

  /** Records boundary calls and can suspend them; authentication still uses the existing fake. */
  private class RecordingRepository(private val fake: FakeAuthRepository) : AuthRepository by fake {
    var calls = 0
    var email: String? = null
    var password: String? = null
    var mode: AuthMode? = null
    var credential: Credential? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> {
      record(email, password, AuthMode.LOGIN)
      return fake.signInWithEmail(email, password)
    }

    override suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser> {
      record(email, password, AuthMode.SIGN_UP)
      return fake.signUpWithEmail(email, password)
    }

    override suspend fun signInWithGoogle(credential: Credential): Result<AuthUser> {
      calls++
      this.credential = credential
      return fake.signInWithGoogle(credential)
    }

    private suspend fun record(email: String, password: String, mode: AuthMode) {
      calls++
      this.email = email
      this.password = password
      this.mode = mode
      gate?.await()
    }
  }
}
