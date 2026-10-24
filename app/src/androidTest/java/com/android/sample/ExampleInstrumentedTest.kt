package com.android.sample

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sample.model.authentication.AuthRepositoryProvider
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.screen.MainScreen
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import io.github.kakaocup.compose.node.element.ComposeScreen
import org.junit.Assert.*
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

  val composeTestRule = createAndroidComposeRule<MainActivity>()
  private val repositoryRule =
      object : ExternalResource() {
        override fun before() {
          AuthRepositoryProvider.repository = FakeAuthRepository()
        }

        override fun after() {
          AuthRepositoryProvider.reset()
        }
      }
  @get:Rule val rules: RuleChain = RuleChain.outerRule(repositoryRule).around(composeTestRule)

  @Test
  fun frenchAndEnglishSurviveActivityRecreation() {
    try {
      for ((tag, title) in listOf("fr" to "Heureux de vous revoir.", "en" to "Welcome back.")) {
        composeTestRule.runOnUiThread {
          AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
        composeTestRule.waitUntil(10000) {
          composeTestRule.activity.resources.configuration.locales[0].language == tag
        }
        composeTestRule.onNodeWithText(title).assertIsDisplayed()
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.onNodeWithText(title).assertIsDisplayed()
      }
    } finally {
      composeTestRule.runOnUiThread {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
      }
    }
  }

  @Test
  fun test() = run {
    step("Start Main Activity") {
      ComposeScreen.onComposeScreen<MainScreen>(composeTestRule) {
        simpleText {
          assertIsDisplayed()
          assertTextEquals("Welcome back.")
        }
      }
    }
  }
}
