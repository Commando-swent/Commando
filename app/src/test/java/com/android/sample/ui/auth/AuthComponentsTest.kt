package com.android.sample.ui.auth

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.android.sample.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
      AuthTheme {
        AuthField(
            "",
            onChange,
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

  @Test
  fun passwordInputIsMaskedAndForwardsTypingAndDone() {
    var typed = ""
    var submissions = 0
    showField(onChange = { typed = it }, onSubmit = { submissions++ })
    compose
        .onNodeWithTag("field")
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        .performTextInput("secret123")
    compose.onNodeWithTag("field").performImeAction()
    compose.runOnIdle {
      assertEquals("secret123", typed)
      assertEquals(1, submissions)
    }
  }

  @Test
  fun mismatchIsDisplayedAndExposedToAccessibility() {
    showField(fieldError = FieldError.PASSWORD_MISMATCH)
    compose.onNodeWithText("Passwords do not match.").assertIsDisplayed()
    compose
        .onNodeWithTag("field")
        .assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.Error,
                "Passwords do not match.",
            )
        )
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
}
