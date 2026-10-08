package com.android.sample

// AI assistance: OpenAI Codex.
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sample.model.authentication.AuthRepositoryProvider
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.screen.MainScreen
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import io.github.kakaocup.compose.node.element.ComposeScreen
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest : TestCase() {

  private val composeTestRule = createAndroidComposeRule<MainActivity>()

  // Install a signed-out session before MainActivity launches, regardless of device history.
  private val signedOutSession =
      object : ExternalResource() {
        override fun before() {
          AuthRepositoryProvider.repository = FakeAuthRepository()
        }

        override fun after() {
          AuthRepositoryProvider.reset()
        }
      }

  @get:Rule val rules: RuleChain = RuleChain.outerRule(signedOutSession).around(composeTestRule)

  @Test
  fun signedOutLaunchShowsTheLoginForm() = run {
    step("Start Main Activity with a signed-out session") {
      ComposeScreen.onComposeScreen<MainScreen>(composeTestRule) {
        loginScreen { assertIsDisplayed() }
      }
      composeTestRule.onNodeWithTag("auth_email").assertExists()
      composeTestRule.onNodeWithTag("auth_password").assertExists()
      composeTestRule.onNodeWithTag("auth_submit").performScrollTo().assertIsEnabled()
    }
  }
}
