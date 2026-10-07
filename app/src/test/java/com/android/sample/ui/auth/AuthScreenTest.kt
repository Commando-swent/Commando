package com.android.sample.ui.auth

import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.credentials.CustomCredential
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.theme.SampleAppTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-mdpi")
class AuthScreenTest {
  @get:Rule val compose = createComposeRule()
  private val fake = FakeAuthRepository()
  private val user = AuthUser("campus-user", email = "student@epfl.ch")
  private val authenticated = mutableListOf<AuthUser>()

  private fun show(
      repository: AuthRepository = fake,
      google: suspend () -> androidx.credentials.Credential? = { null },
  ) {
    val viewModel = AuthViewModel(repository)
    compose.setContent { SampleAppTheme { AuthRoute(viewModel, google, authenticated::add, {}) } }
  }

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  private fun fillLogin() {
    node("auth_email").performTextInput("student@epfl.ch")
    node("auth_password").performTextInput("secret123")
  }

  @Test
  fun tabsAndFooterSwitchModesAndClearPasswords() {
    show()
    fillLogin()
    node("auth_password")
        .assert(
            SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.Password
            )
        )
    node("auth_tab_signup").performClick().assertIsSelected()
    node("auth_email").assertTextContains("student@epfl.ch")
    node("auth_password")
        .assert(
            SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString(""),
            )
        )
    node("auth_confirmation").assertExists()
    node("auth_switch").performScrollTo().performClick()
    node("auth_tab_login").assertIsSelected()
    node("auth_confirmation").assertDoesNotExist()
  }

  @Test
  fun missingFieldsAndInvalidEmailAreVisible() {
    show()
    node("auth_submit").performClick()
    compose.onAllNodesWithText("This field is required.").assertCountEquals(2)
    node("auth_email").performTextInput("invalid")
    node("auth_password").performTextInput("secret123")
    node("auth_submit").performClick()
    compose.onNodeWithText("Enter a valid email address.").assertIsDisplayed()
    assertNull(fake.currentUser)
  }

  @Test
  fun signupRequiresMatchingConfirmationAndCanSucceed() {
    fake.signUpWithEmailResult = Result.success(user)
    show()
    node("auth_tab_signup").performClick()
    fillLogin()
    node("auth_submit").performClick()
    compose.onNodeWithText("This field is required.").assertIsDisplayed()
    node("auth_confirmation").performTextInput("different")
    node("auth_submit").performClick()
    compose.onNodeWithText("Passwords do not match.").assertIsDisplayed()
    assertTrue(authenticated.isEmpty())
    node("auth_confirmation").performTextReplacement("secret123")
    node("auth_submit").performClick()
    compose.runOnIdle {
      assertEquals(user, fake.currentUser)
      assertEquals(listOf(user), authenticated)
    }
  }

  @Test
  fun authenticationErrorAllowsRetryAndSuccessIsDeliveredOnce() {
    fake.signInWithEmailResult = Result.failure(AuthException.InvalidCredentials())
    show()
    fillLogin()
    node("auth_submit").performClick()
    compose.onNodeWithText("Incorrect email or password. Please try again.").assertIsDisplayed()
    compose.runOnIdle { fake.signInWithEmailResult = Result.success(user) }
    node("auth_submit").performClick()
    compose.runOnIdle { assertEquals(listOf(user), authenticated) }
    node("auth_submit").assertIsNotEnabled()
    node("auth_google").assertIsNotEnabled()
  }

  @Test
  fun loadingDisablesFieldsAndAllSubmissionAndNavigationControls() {
    val gate = CompletableDeferred<Unit>()
    var submissions = 0
    val delayed =
        object : AuthRepository by fake {
          override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> {
            submissions++
            gate.await()
            return Result.failure(AuthException.Network())
          }
        }
    show(delayed)
    fillLogin()
    node("auth_submit").performClick()
    compose.onNodeWithText("Signing in…").assertIsDisplayed()
    listOf(
            "auth_email",
            "auth_password",
            "auth_submit",
            "auth_google",
            "auth_tab_signup",
            "auth_switch",
            "auth_back",
        )
        .forEach { node(it).assertIsNotEnabled() }
    node("auth_submit").performClick()
    compose.runOnIdle {
      assertEquals(1, submissions)
      gate.complete(Unit)
    }
    compose.onNodeWithText("Check your connection and try again.").assertIsDisplayed()
    node("auth_submit").assertIsEnabled()
  }

  @Test
  fun googleButtonRequestsCredentialAndAuthenticatesWithoutEmailFields() {
    fake.signInWithGoogleResult = Result.success(user)
    var requests = 0
    show(
        google = {
          requests++
          CustomCredential("test-google", Bundle())
        }
    )
    node("auth_google").performClick()
    compose.runOnIdle {
      assertEquals(1, requests)
      assertEquals(user, fake.currentUser)
      assertEquals(listOf(user), authenticated)
    }
  }

  @Test
  fun cancelledGooglePickerLeavesFormUsableAndBackCallsOwner() {
    val viewModel = AuthViewModel(fake)
    var backs = 0
    compose.setContent {
      SampleAppTheme { AuthRoute(viewModel, { null }, authenticated::add, { backs++ }) }
    }
    node("auth_google").performClick()
    node("auth_submit").assertIsEnabled()
    node("auth_back").performClick()
    compose.runOnIdle {
      assertEquals(1, backs)
      assertTrue(authenticated.isEmpty())
    }
  }
}
