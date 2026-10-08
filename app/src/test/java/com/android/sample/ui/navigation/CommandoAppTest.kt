package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.credentials.Credential
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.ClearCredentialUnknownException
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepositoryProvider
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.auth.GoogleSignInNotConfiguredException
import com.android.sample.ui.home.HomeTestTags
import com.android.sample.ui.theme.SampleAppTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class CommandoAppTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val alice = AuthUser("alice")
  private val bob = AuthUser("bob")

  private class Picker : GoogleCredentialClient {
    var credential: Credential? = null
    var error: Exception? = null
    var clearError: Exception? = null
    var clearCalls = 0
    var clearGate: CompletableDeferred<Unit>? = null

    override suspend fun request(context: Context): Credential? {
      error?.let { throw it }
      return credential
    }

    override suspend fun clearSession() {
      clearCalls++
      clearGate?.await()
      clearError?.let { throw it }
    }
  }

  private fun show(repository: FakeAuthRepository, picker: Picker = Picker()) {
    val trips = FakeTripRepository(currentUserId = "alice")
    compose.setContent {
      SampleAppTheme { CommandoApp(repository = repository, tripRepository = trips, googleCredentials = picker) }
    }
  }

  private fun assertScreen(tag: String) {
    compose.onNodeWithTag(tag).assertIsDisplayed()
  }

  private fun click(tag: String) {
    compose.onNodeWithTag(tag).performClick()
  }

  private fun pressBack() {
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
  }

  @Test
  fun authDoesNotDisplaySharedBars() {
    show(FakeAuthRepository())
    compose.onNodeWithTag(AppTestTags.APP_SCAFFOLD).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.BOTTOM_BAR).assertDoesNotExist()
  }

  @Test
  fun bottomBarTracksDestinationAndHomeReturnsWithoutDuplicatingTheStack() {
    show(FakeAuthRepository(alice))
    compose.onNodeWithTag(AppTestTags.HOME_BUTTON).assertIsSelected()
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.PROFILE_BUTTON)
    compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertDoesNotExist()
    click(AppTestTags.PROFILE_BUTTON)
    click(AppTestTags.HOME_BUTTON)
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.HOME_BUTTON).assertIsSelected()
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
  }

  @Test
  fun switchingModeKeepsTheAccountAndProfileBackKeepsTheMode() {
    val repository = FakeAuthRepository(alice)
    show(repository)
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsNotSelected()
    click(AppTestTags.COMMANDO_MODE)
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsNotSelected()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
    compose
        .onNodeWithTag(HomeTestTags.EMPTY_STATE_TITLE)
        .assertTextEquals("Heading to the shop soon?")
    assertEquals(alice, repository.currentUser)
    click(AppTestTags.PROFILE_BUTTON)
    pressBack()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
    click(AppTestTags.REQUESTER_MODE)
    compose.onNodeWithTag(HomeTestTags.EMPTY_STATE_TITLE).assertTextEquals("Need a few things?")
    assertEquals(alice, repository.currentUser)
  }

  @Test
  fun signingOutAndBackIntoTheSameAccountResetsTheMode() = runTest {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsSelected()
  }

  @Test
  fun changingAccountResetsHomeMode() = runTest {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.COMMANDO_MODE)
    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsSelected()
    assertEquals(bob, repository.currentUser)
  }

  @Test
  fun signedOutUserSeesAuthAndCanSwitchForms() {
    show(FakeAuthRepository())
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    click("auth_tab_signup")
    assertScreen(NavigationTestTags.SIGN_UP_SCREEN)
    click("auth_tab_login")
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertDoesNotExist()
  }

  @Test
  fun existingSessionStartsOnHomeAndProfileReturnsWithBack() {
    show(FakeAuthRepository(alice))
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(AppTestTags.PROFILE_BUTTON)
    assertScreen(NavigationTestTags.PROFILE_SCREEN)
    pressBack()
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.LOGIN_SCREEN).assertDoesNotExist()
  }

  @Test
  fun signingInRemovesAuthFromBackStack() = runTest {
    val repository = FakeAuthRepository()
    show(repository)
    click("auth_tab_signup")
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
    compose.onNodeWithTag(NavigationTestTags.SIGN_UP_SCREEN).assertDoesNotExist()
  }

  @Test
  fun signingOutFromProfilePreventsBackToProtectedScreens() {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    assertNull(repository.currentUser)
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
  }

  @Test
  fun externalSignOutAlsoRemovesProtectedScreens() {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.PROFILE_BUTTON)
    compose.runOnIdle { repository.signOut() }
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
  }

  @Test
  fun failedSignOutKeepsProfileAndAllowsRetry() {
    val repository = FakeAuthRepository(alice)
    repository.signOutResult = Result.failure(AuthException.Network())
    val picker = Picker()
    show(repository, picker)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.PROFILE_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.SIGN_OUT_ERROR).assertIsDisplayed()
    assertEquals(alice, repository.currentUser)
    compose.runOnIdle { assertEquals(0, picker.clearCalls) }
    repository.signOutResult = Result.success(Unit)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.runOnIdle { assertEquals(1, picker.clearCalls) }
  }

  @Test
  fun changingAccountStartsAtHomeInsteadOfPreviousProfile() = runTest {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.PROFILE_BUTTON)
    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
  }

  @Test
  fun profileUpdateKeepsNavigationForTheSameAccount() = runTest {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.PROFILE_BUTTON)
    repository.signInWithEmailResult = Result.success(alice.copy(displayName = "Alice"))
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.PROFILE_SCREEN)
  }

  @Test
  fun signingOutRecreatesTheLoginForm() = runTest {
    val repository = FakeAuthRepository()
    show(repository)
    click("auth_tab_signup")
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.SIGN_UP_SCREEN).assertDoesNotExist()
  }

  @After
  fun resetProvider() {
    AuthRepositoryProvider.reset()
  }

  private fun fillEmailForm() {
    compose.onNodeWithTag("auth_email").performTextInput("student@epfl.ch")
    compose.onNodeWithTag("auth_password").performTextInput("secret123")
  }

  @Test
  fun defaultAppUsesProviderAndEmailLoginReachesHome() {
    val repository = FakeAuthRepository()
    repository.signInWithEmailResult = Result.success(alice)
    AuthRepositoryProvider.repository = repository
    compose.setContent { SampleAppTheme { CommandoApp(tripRepository = FakeTripRepository(currentUserId = alice.uid), googleCredentials = Picker()) } }
    fillEmailForm()
    click("auth_submit")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    assertEquals(alice, repository.currentUser)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.onNodeWithTag("auth_email").assertTextEquals("", "Your university email")
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
  }

  @Test
  fun signupThroughFormReachesHome() {
    val repository = FakeAuthRepository()
    repository.signUpWithEmailResult = Result.success(alice)
    show(repository)
    click("auth_tab_signup")
    fillEmailForm()
    compose.onNodeWithTag("auth_confirmation").performTextInput("secret123")
    click("auth_submit")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    assertEquals(alice, repository.currentUser)
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
  }

  @Test
  fun failedEmailLoginKeepsFormAndCanRetry() {
    val repository = FakeAuthRepository()
    repository.signInWithEmailResult = Result.failure(AuthException.InvalidCredentials())
    show(repository)
    fillEmailForm()
    click("auth_submit")
    compose.onNodeWithText("Incorrect email or password. Please try again.").assertIsDisplayed()
    assertNull(repository.currentUser)
    compose.runOnIdle { repository.signInWithEmailResult = Result.success(alice) }
    click("auth_submit")
    assertScreen(NavigationTestTags.HOME_SCREEN)
  }

  @Test
  fun missingGoogleConfigurationShowsFeedbackAndEmailStillWorks() {
    val repository = FakeAuthRepository()
    repository.signInWithGoogleResult = Result.success(alice)
    show(repository, Picker().apply { error = GoogleSignInNotConfiguredException() })
    click("auth_google")
    compose.runOnIdle {
      assertEquals(
          "Google sign-in needs Firebase configuration. Please use email and password for now.",
          ShadowToast.getTextOfLatestToast(),
      )
      assertNull(repository.currentUser)
    }
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    fillEmailForm()
    repository.signInWithEmailResult = Result.success(alice)
    click("auth_submit")
    assertScreen(NavigationTestTags.HOME_SCREEN)
  }

  @Test
  fun googleCredentialAuthenticatesAndSignOutClearsPickerSession() {
    val repository = FakeAuthRepository()
    repository.signInWithGoogleResult = Result.success(alice)
    val picker = Picker().apply { credential = CustomCredential("google", Bundle()) }
    show(repository, picker)
    click("auth_google")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    assertEquals(alice, repository.currentUser)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.runOnIdle { assertEquals(1, picker.clearCalls) }
  }

  @Test
  fun dismissingPickerDoesNotAuthenticateAndSignupGoogleCanRetry() {
    val repository = FakeAuthRepository()
    repository.signInWithGoogleResult = Result.success(alice)
    val picker = Picker()
    show(repository, picker)
    click("auth_tab_signup")
    click("auth_google")
    assertScreen(NavigationTestTags.SIGN_UP_SCREEN)
    compose.runOnIdle {
      assertNull(repository.currentUser)
      picker.credential = CustomCredential("google", Bundle())
    }
    click("auth_google")
    assertScreen(NavigationTestTags.HOME_SCREEN)
  }

  @Test
  fun pickerFailureShowsSafeErrorAndAllowsRetry() {
    val repository = FakeAuthRepository()
    repository.signInWithGoogleResult = Result.success(alice)
    val picker = Picker().apply { error = AuthException.InvalidGoogleCredential() }
    show(repository, picker)
    click("auth_google")
    compose.onNodeWithText("Google sign-in failed. Please try again.").assertIsDisplayed()
    compose.runOnIdle {
      assertNull(repository.currentUser)
      picker.error = null
      picker.credential = CustomCredential("google", Bundle())
    }
    click("auth_google")
    assertScreen(NavigationTestTags.HOME_SCREEN)
  }

  @Test
  fun providerCleanupFailureStillSignsOutOfFirebase() {
    val repository = FakeAuthRepository(alice)
    val picker = Picker().apply { clearError = ClearCredentialUnknownException() }
    show(repository, picker)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    assertNull(repository.currentUser)
    compose.runOnIdle { assertEquals(1, picker.clearCalls) }
  }

  @Test
  fun suspendedProviderCleanupDoesNotDelaySignOutOrPreserveProtectedScreens() {
    val repository = FakeAuthRepository(alice)
    val gate = CompletableDeferred<Unit>()
    val picker = Picker().apply { clearGate = gate }
    show(repository, picker)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    try {
      assertScreen(NavigationTestTags.LOGIN_SCREEN)
      compose.runOnIdle {
        assertNull(repository.currentUser)
        assertEquals(1, picker.clearCalls)
        assertTrue(!gate.isCompleted)
      }
      compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
      pressBack()
      compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertDoesNotExist()
    } finally {
      compose.runOnIdle { gate.complete(Unit) }
    }
  }
}
