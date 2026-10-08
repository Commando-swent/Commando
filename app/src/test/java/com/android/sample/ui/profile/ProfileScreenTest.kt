package com.android.sample.ui.profile

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.model.authentication.AuthException
import com.android.sample.ui.navigation.*
import com.android.sample.ui.theme.SampleAppTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w412dp-h830dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun profileRendersAccountInformationWithinTheSharedScaffold() {
    compose.setContent {
      SampleAppTheme {
        AppScaffold(CommandoScreens.Profile, AppMode.Requester, {}, {}, {}) { padding ->
          ProfileScreen(
              ProfileUiState.Content("Élodie van der Meer", "Elodie+work@epfl.ch"),
              onBack = {},
              onSignOut = {},
              modifier = Modifier.padding(padding),
          )
        }
      }
    }
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.SCREEN_TITLE).assertTextEquals("Profile")
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("Élodie van der Meer")
    compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals("Elodie+work@epfl.ch")
    compose.onNodeWithTag(ProfileTestTags.INITIALS).assertTextEquals("ÉM")
    val file = File("build/reports/profile/profile.png")
    file.parentFile?.mkdirs()
    file.outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  @Test
  fun loadingAndMissingSessionRemovePreviousProfileInformation() {
    val profile = ProfileUiState.Content("Alice Martin", "alice@epfl.ch")
    val state = mutableStateOf<ProfileUiState>(profile)
    compose.setContent { SampleAppTheme { ProfileScreen(state.value, {}, {}) } }
    val emptyStates =
        listOf(
            ProfileUiState.Loading to ProfileTestTags.LOADING,
            ProfileUiState.MissingUser to ProfileTestTags.MISSING_USER,
        )
    for ((next, tag) in emptyStates) {
      compose.runOnIdle { state.value = profile }
      compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("Alice Martin")
      compose.runOnIdle { state.value = next }
      compose.onNodeWithTag(tag).assertIsDisplayed()
      for (hidden in
          listOf(
              ProfileTestTags.FULL_NAME,
              ProfileTestTags.EMAIL,
              ProfileTestTags.INITIALS,
              ProfileTestTags.NEUTRAL_AVATAR,
              ProfileTestTags.EDIT,
              ProfileTestTags.ADD_PHOTO,
          )) {
        compose.onNodeWithTag(hidden).assertDoesNotExist()
      }
      compose.onNodeWithText("Alice Martin").assertDoesNotExist()
      compose.onNodeWithText("alice@epfl.ch").assertDoesNotExist()
    }
  }

  @Test
  fun backAndSignOutCallbacksAndSafeErrorAllowRetryWithoutLosingTheProfile() {
    var backCalls = 0
    var signOutCalls = 0
    val error =
        mutableStateOf<AuthException?>(AuthException.Network(IllegalStateException("private")))
    compose.setContent {
      SampleAppTheme {
        ProfileScreen(
            ProfileUiState.Content("Alice Martin", "alice@epfl.ch"),
            onBack = { backCalls++ },
            onSignOut = { signOutCalls++ },
            signOutError = error.value,
        )
      }
    }
    compose.onNodeWithTag(NavigationTestTags.BACK_BUTTON).performClick()
    compose.runOnIdle {
      assertEquals(1, backCalls)
      assertEquals(0, signOutCalls)
    }
    compose.onNodeWithTag(NavigationTestTags.SIGN_OUT_ERROR).assertIsDisplayed()
    compose.onNodeWithText("private").assertDoesNotExist()
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("Alice Martin")
    compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals("alice@epfl.ch")
    compose.onNodeWithTag(NavigationTestTags.SIGN_OUT_BUTTON).performClick()
    compose.runOnIdle {
      assertEquals(1, signOutCalls)
      error.value = null
    }
    compose.onNodeWithTag(NavigationTestTags.SIGN_OUT_ERROR).assertDoesNotExist()
    compose.onNodeWithTag(NavigationTestTags.SIGN_OUT_BUTTON).performClick()
    compose.runOnIdle {
      assertEquals(2, signOutCalls)
      assertEquals(1, backCalls)
    }
  }
}
