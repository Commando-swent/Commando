package com.android.sample.ui.auth

// AI assistance: OpenAI Codex.
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import com.android.sample.ui.theme.SampleAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h915dp-mdpi")
class WelcomeScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun figmaCopyAndBothActionsAreAvailable() {
    assertActions("Log in", "Create account", 1f)
    compose.onNodeWithText("Groceries\nmade easy\non campus.").assertExists()
  }

  @Test
  @Config(qualifiers = "fr-rFR-w320dp-h640dp-mdpi")
  fun frenchActionsRemainReachableWithLargeTextOnNarrowScreen() {
    assertActions("Se connecter", "Créer un compte", 1.5f)
    compose.onNodeWithText("Les courses\nsimplifiées\nsur le campus.").assertExists()
  }

  private fun assertActions(login: String, signup: String, fontScale: Float) {
    var logins = 0
    var signups = 0
    compose.setContent {
      CompositionLocalProvider(
          LocalDensity provides Density(LocalDensity.current.density, fontScale)
      ) {
        SampleAppTheme { WelcomeScreen({ logins++ }, { signups++ }) }
      }
    }
    compose
        .onNodeWithTag(WelcomeTestTags.LOGIN)
        .performScrollTo()
        .assertIsDisplayed()
        .assert(hasText(login, substring = true))
        .performClick()
    compose
        .onNodeWithTag(WelcomeTestTags.CREATE_ACCOUNT)
        .performScrollTo()
        .assertIsDisplayed()
        .assert(hasText(signup))
        .performClick()
    compose.runOnIdle {
      assertEquals(1, logins)
      assertEquals(1, signups)
    }
  }
}
