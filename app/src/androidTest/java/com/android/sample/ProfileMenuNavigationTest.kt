package com.android.sample

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.credentials.Credential
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.navigation.AppTestTags
import com.android.sample.ui.navigation.CommandoApp
import com.android.sample.ui.navigation.NavigationTestTags
import com.android.sample.ui.profile.ProfileTestTags
import com.android.sample.ui.theme.SampleAppTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises Profile menu navigation and session cleanup on Android with isolated in-memory data.
 */
@RunWith(AndroidJUnit4::class)
class ProfileMenuNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private fun capture(name: String) {
    val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    File(files, "$name.png").outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  @Test
  fun menuReturnsPreservePositionAndSignOutClearsHelp() {
    val user = AuthUser("profile-menu-test", "Alex Morgan", "alex@example.com")
    val auth = FakeAuthRepository(user)
    val credentials =
        object : GoogleCredentialClient {
          override suspend fun request(context: Context): Credential? = null

          override suspend fun clearSession() = Unit
        }
    compose.setContent {
      SampleAppTheme {
        Box(Modifier.height(640.dp)) {
          CommandoApp(auth, FakeTripRepository(user.uid), credentials)
        }
      }
    }
    compose.onNodeWithTag(AppTestTags.ORDERS_BUTTON).performClick()
    compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).performClick()
    val scroll =
        compose.onNode(
            hasScrollAction() and hasAnyAncestor(hasTestTag(NavigationTestTags.PROFILE_SCREEN))
        )
    for (category in listOf(ProfileTestTags.HELP, ProfileTestTags.ORDER_HISTORY)) {
      compose.onNodeWithTag(ProfileTestTags.HELP).performScrollTo()
      val before =
          scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
      assertTrue(before > 0f)
      compose.onNodeWithTag(category).performClick()
      if (category == ProfileTestTags.HELP) {
        compose.onNodeWithText("Popular questions").assertIsDisplayed()
        val question = compose.onNodeWithTag("${ProfileTestTags.FAQ_QUESTION}_0")
        question.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")
        )
        question.performClick()
        question.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")
        )
        question.performClick()
        compose.onNodeWithTag(ProfileTestTags.SETTINGS_BACK).performClick()
      } else {
        compose.onNodeWithText("My Orders").assertIsDisplayed()
        compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).performClick()
      }
      val after =
          scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
      assertEquals(before, after, 1f)
    }
    capture("pr1-profile")
    compose.onNodeWithTag(ProfileTestTags.HELP).performClick()
    capture("pr1-faq")
    compose.onNodeWithText("info.commando@gmail.com").performScrollTo().assertIsDisplayed()
    compose.onNode(hasSetTextAction()).assertDoesNotExist()
    capture("pr1-contact")
    compose.runOnIdle { auth.signOut() }
    compose.onNodeWithTag(NavigationTestTags.LOGIN_SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTestTags.HELP_SCREEN).assertDoesNotExist()
  }
}
