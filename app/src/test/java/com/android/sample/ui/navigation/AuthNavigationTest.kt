package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.credentials.Credential
import androidx.credentials.CustomCredential
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepositoryProvider
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.theme.SampleAppTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-mdpi")
class AuthNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val repository = FakeAuthRepository()
  private val alice = AuthUser("alice", email = "alice@epfl.ch")
  private var clearCalls = 0

  @After
  fun resetProvider() {
    AuthRepositoryProvider.reset()
  }

  private fun show(google: suspend () -> Credential? = { null }) {
    AuthRepositoryProvider.repository = repository
    compose.setContent {
      SampleAppTheme {
        // Exercise the production default: form and session use the shared provider.
        CommandoApp(requestGoogleCredential = google, clearCredentialState = { clearCalls++ })
      }
    }
  }

  private fun click(tag: String) {
    val node = compose.onNodeWithTag(tag)
    if (tag.startsWith("auth_")) node.performScrollTo()
    node.performClick()
  }

  private fun fillForm() {
    compose.onNodeWithTag("auth_email").performTextInput("alice@epfl.ch")
    compose.onNodeWithTag("auth_password").performTextInput("secret123")
  }

  @Test
  fun emailLoginOpensHomeAndSignOutReturnsToAFreshForm() {
    repository.signInWithEmailResult = Result.success(alice)
    show()
    fillForm()
    click("auth_submit")
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
    compose.runOnIdle { assertEquals(alice, repository.currentUser) }
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    compose.onNodeWithTag(NavigationTestTags.LOGIN_SCREEN).assertIsDisplayed()
    compose
        .onNodeWithTag("auth_password")
        .assert(
            SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString(""),
            )
        )
    compose.runOnIdle {
      assertNull(repository.currentUser)
      assertEquals(1, clearCalls)
    }
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
  }

  @Test
  fun signUpUsesTheSharedSessionAndOpensHome() {
    repository.signUpWithEmailResult = Result.success(alice)
    show()
    click("auth_tab_signup")
    fillForm()
    compose.onNodeWithTag("auth_confirmation").performTextInput("secret123")
    click("auth_submit")
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.SIGN_UP_SCREEN).assertDoesNotExist()
    compose.runOnIdle { assertEquals(alice, repository.currentUser) }
  }

  @Test
  fun failedLoginStaysOnFormAndCanRetry() {
    repository.signInWithEmailResult = Result.failure(AuthException.InvalidCredentials())
    show()
    fillForm()
    click("auth_submit")
    compose.onNodeWithText("Incorrect email or password. Please try again.").assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertDoesNotExist()
    repository.signInWithEmailResult = Result.success(alice)
    click("auth_submit")
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
  }

  @Test
  fun googleSuccessOpensHomeWithoutEmailInput() {
    repository.signInWithGoogleResult = Result.success(alice)
    var requests = 0
    show {
      requests++
      CustomCredential("test-google", Bundle())
    }
    click("auth_google")
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
    compose.runOnIdle { assertEquals(1, requests) }
  }

  @Test
  fun cancelledGooglePickerLeavesEmailLoginUsable() {
    show { null }
    click("auth_google")
    compose.onNodeWithTag(NavigationTestTags.LOGIN_SCREEN).assertIsDisplayed()
    compose.onNodeWithTag("auth_submit").assertIsEnabled()
    repository.signInWithEmailResult = Result.success(alice)
    fillForm()
    click("auth_submit")
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
  }

  @Test
  fun failedSignOutDoesNotClearGoogleSession() {
    repository.signInWithEmailResult = Result.success(alice)
    repository.signOutResult = Result.failure(AuthException.Network())
    show()
    fillForm()
    click("auth_submit")
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    compose.onNodeWithTag(NavigationTestTags.SIGN_OUT_ERROR).assertIsDisplayed()
    compose.runOnIdle { assertEquals(0, clearCalls) }
  }

  @Test
  fun backFromSignUpReturnsToLoginAndBackFromLoginExits() {
    show()
    click("auth_tab_signup")
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(NavigationTestTags.LOGIN_SCREEN).assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
  }
}
