package com.commando.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.android.sample.R
import com.commando.app.model.authentication.AuthRepositoryProvider
import com.commando.app.model.authentication.FakeAuthRepository
import com.commando.app.ui.auth.WelcomeTestTags
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the migrated launcher with real activity content and a signed-out test identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rCH-w412dp-h917dp-mdpi")
class MainActivityLaunchTest {
  private val compose = createAndroidComposeRule<MainActivity>()
  private val identity =
      object : ExternalResource() {
        override fun before() {
          AuthRepositoryProvider.repository = FakeAuthRepository()
          val context = ApplicationProvider.getApplicationContext<Context>()
          if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:123:android:launcher-test")
                    .setApiKey("test-api-key")
                    .setProjectId("demo-commando")
                    .build(),
            )
          }
        }

        override fun after() = AuthRepositoryProvider.reset()
      }

  @get:Rule val rules: RuleChain = RuleChain.outerRule(identity).around(compose)

  @Test
  fun migratedLauncherRestoresLocalizedWelcomeAfterRecreation() {
    val activity = compose.activity
    val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
    assertEquals(MainActivity::class.java.name, launch?.component?.className)
    assertEquals("fr", activity.resources.configuration.locales[0].language)
    val login = activity.getString(R.string.auth_login_action)
    compose.onNodeWithTag(WelcomeTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTestTags.LOGIN).assertTextEquals(login)
    compose.activityRule.scenario.recreate()
    compose.onNodeWithTag(WelcomeTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTestTags.LOGIN).assertTextEquals(login)
  }
}
