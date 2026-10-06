package com.android.sample.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.android.sample.R
import com.android.sample.model.authentication.AuthException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthComponentsTest {
  @get:Rule val compose = createComposeRule()

  private fun showField(
      fieldError: FieldError? = null,
      enabled: Boolean = true,
      onChange: (String) -> Unit = {},
      onSubmit: () -> Unit = {},
  ) {
    compose.setContent {
      var value by remember { mutableStateOf("") }
      AuthTheme {
        AuthField(
            value,
            {
              value = it
              onChange(it)
            },
            R.string.auth_password,
            R.string.auth_password_hint,
            "field",
            fieldError,
            enabled,
            password = true,
            done = true,
            onSubmit,
        )
      }
    }
  }

  private fun assertFieldError(message: String) {
    compose.onNodeWithText(message).assertIsDisplayed()
    compose
        .onNodeWithTag("field")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, message))
  }

  @Test
  fun passwordInputIsMaskedAndForwardsTypingAndDone() {
    var typed = ""
    var submissions = 0
    showField(onChange = { typed = it }, onSubmit = { submissions++ })
    compose
        .onNodeWithTag("field")
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        .performTextInput("secret123")
    compose.onNodeWithTag("field").assertTextEquals("•••••••••")
    compose.onNodeWithText("Enter your password").assertDoesNotExist()
    compose.onNodeWithTag("field").performImeAction()
    compose.runOnIdle {
      assertEquals("secret123", typed)
      assertEquals(1, submissions)
    }
  }

  @Test
  fun mismatchIsDisplayedAndExposedToAccessibility() {
    showField(fieldError = FieldError.PASSWORD_MISMATCH)
    assertFieldError("Passwords do not match.")
  }

  @Test
  fun disabledFieldPreventsKeyboardSubmission() {
    var submissions = 0
    showField(enabled = false, onSubmit = { submissions++ })
    compose.onNodeWithTag("field").assertIsNotEnabled().performSemanticsAction(
        androidx.compose.ui.semantics.SemanticsActions.OnImeAction
    ) {
      it()
    }
    compose.runOnIdle { assertEquals(0, submissions) }
  }

  @Test
  fun requiredFieldErrorIsDisplayedAndAccessible() {
    showField(fieldError = FieldError.REQUIRED)
    assertFieldError("This field is required.")
  }

  @Test
  fun invalidEmailExplainsHowToCorrectTheField() {
    showField(fieldError = FieldError.INVALID_EMAIL)
    assertFieldError("Enter a valid email address.")
  }

  @Test
  fun emailIsVisibleAndNextMovesFocusWithoutSubmitting() {
    var submissions = 0
    compose.setContent {
      AuthTheme {
        Column {
          AuthField(
              "adam@epfl.ch",
              {},
              R.string.auth_email,
              R.string.auth_email_hint,
              "email",
              null,
              true,
              false,
              false,
          ) {
            submissions++
          }
          BasicTextField("", {}, Modifier.testTag("next"))
        }
      }
    }
    compose
        .onNodeWithTag("email")
        .assertTextEquals("adam@epfl.ch")
        .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        .performClick()
        .performImeAction()
    compose.onNodeWithTag("next").assertIsFocused()
    compose.runOnIdle { assertEquals(0, submissions) }
  }

  @Test
  fun authenticationFailuresUseSafeActionableMessages() {
    val failures =
        listOf(
            AuthException.InvalidEmail() to "Enter a valid email address.",
            AuthException.EmailAlreadyInUse() to
                "This email is already registered. Try logging in.",
            AuthException.AccountConflict() to
                "Use the sign-in method already linked to this email.",
            AuthException.InvalidCredentials() to "Incorrect email or password. Please try again.",
            AuthException.InvalidGoogleCredential() to "Google sign-in failed. Please try again.",
            AuthException.Network() to "Check your connection and try again.",
            AuthException.TooManyRequests() to "Too many attempts. Please try again later.",
            AuthException.Unknown(IllegalStateException("private provider details")) to
                "Unable to sign in. Please try again.",
        )
    failures.forEach { (failure, expected) ->
      assertEquals(
          failure.javaClass.simpleName,
          expected,
          RuntimeEnvironment.getApplication().getString(failure.messageResource()),
      )
    }
  }
}
