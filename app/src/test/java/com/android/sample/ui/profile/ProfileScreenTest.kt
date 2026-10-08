package com.android.sample.ui.profile

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.android.sample.R
import com.android.sample.model.authentication.AuthException
import com.android.sample.ui.navigation.AppMode
import com.android.sample.ui.navigation.AppScaffold
import com.android.sample.ui.navigation.CommandoScreens
import com.android.sample.ui.navigation.NavigationTestTags
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

  private val fullName = "Alice Martin"
  private val email = "alice@epfl.ch"
  private val profile = ProfileUiState.Content(fullName, email)

  private fun show(
      state: ProfileUiState,
      onBack: () -> Unit = {},
      onSignOut: () -> Unit = {},
      signOutError: AuthException? = null,
  ) {
    compose.setContent {
      SampleAppTheme {
        ProfileScreen(
            uiState = state,
            onBack = onBack,
            onSignOut = onSignOut,
            signOutError = signOutError,
        )
      }
    }
  }

  @Test
  fun fullProfileDisplaysActualValuesAndInitialsInTheAppScaffold() {
    val suppliedName = "  Élodie van der Meer  "
    val suppliedEmail = "Elodie+work@epfl.ch"
    compose.setContent {
      SampleAppTheme {
        AppScaffold(
            currentScreen = CommandoScreens.Profile,
            mode = AppMode.Requester,
            onSwitchMode = {},
            onHome = {},
            onProfile = {},
        ) { padding ->
          ProfileScreen(
              uiState = ProfileUiState.Content(suppliedName, suppliedEmail),
              onBack = {},
              onSignOut = {},
              modifier = Modifier.padding(padding),
          )
        }
      }
    }

    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertIsDisplayed()
    compose
        .onNodeWithTag(NavigationTestTags.SCREEN_TITLE)
        .assertTextEquals(compose.activity.getString(R.string.nav_profile))
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals(suppliedName)
    compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals(suppliedEmail)
    compose.onNodeWithTag(ProfileTestTags.INITIALS).assertTextEquals("ÉM")
    compose.onNodeWithTag(ProfileTestTags.NEUTRAL_AVATAR).assertDoesNotExist()
    compose.onNodeWithTag(ProfileTestTags.EDIT).assertIsDisplayed().assertIsNotEnabled()
    compose
        .onNodeWithTag(ProfileTestTags.ADD_PHOTO)
        .assertIsDisplayed()
        .assertIsNotEnabled()
        .assertContentDescriptionEquals(
            compose.activity.getString(R.string.profile_add_photo_description)
        )
    saveScreenshot()
  }

  @Test
  fun singleWordNameDisplaysOneInitial() {
    show(ProfileUiState.Content("yasmine", "yasmine@epfl.ch"))

    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("yasmine")
    compose.onNodeWithTag(ProfileTestTags.INITIALS).assertTextEquals("Y")
  }

  @Test
  fun absentNameKeepsTheEmailAndUsesANeutralAvatar() {
    val state = mutableStateOf<ProfileUiState>(profile)
    compose.setContent {
      SampleAppTheme { ProfileScreen(uiState = state.value, onBack = {}, onSignOut = {}) }
    }

    for (name in listOf(null, "", " \t\n ")) {
      compose.runOnIdle { state.value = ProfileUiState.Content(name, profile.email) }

      compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("—")
      compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals(email)
      compose.onNodeWithTag(ProfileTestTags.NEUTRAL_AVATAR).assertIsDisplayed()
      compose.onNodeWithTag(ProfileTestTags.INITIALS).assertDoesNotExist()
      compose.onNodeWithText(fullName).assertDoesNotExist()
    }
  }

  @Test
  fun absentEmailKeepsTheNameAndInitials() {
    val state = mutableStateOf<ProfileUiState>(profile)
    compose.setContent {
      SampleAppTheme { ProfileScreen(uiState = state.value, onBack = {}, onSignOut = {}) }
    }

    for (missingEmail in listOf(null, "", " \t\n ")) {
      compose.runOnIdle { state.value = ProfileUiState.Content(profile.fullName, missingEmail) }

      compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals(fullName)
      compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals("—")
      compose.onNodeWithTag(ProfileTestTags.INITIALS).assertTextEquals("AM")
      compose.onNodeWithText(email).assertDoesNotExist()
    }
  }

  @Test
  fun bothMissingFieldsDisplayNoFabricatedProfileData() {
    show(ProfileUiState.Content(null, null))

    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("—")
    compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals("—")
    compose.onNodeWithTag(ProfileTestTags.NEUTRAL_AVATAR).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTestTags.INITIALS).assertDoesNotExist()
  }

  @Test
  fun loadingShowsNoPersonalInformation() {
    show(ProfileUiState.Loading)

    compose.onNodeWithTag(ProfileTestTags.LOADING).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTestTags.MISSING_USER).assertDoesNotExist()
    assertNoProfileData()
  }

  @Test
  fun missingUserShowsNoPersonalInformation() {
    show(ProfileUiState.MissingUser)

    compose.onNodeWithTag(ProfileTestTags.MISSING_USER).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTestTags.LOADING).assertDoesNotExist()
    assertNoProfileData()
  }

  @Test
  fun stateTransitionsRemoveAllPreviousProfileData() {
    val state = mutableStateOf<ProfileUiState>(profile)
    compose.setContent {
      SampleAppTheme { ProfileScreen(uiState = state.value, onBack = {}, onSignOut = {}) }
    }
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals(fullName)

    compose.runOnIdle { state.value = ProfileUiState.Loading }
    compose.onNodeWithTag(ProfileTestTags.LOADING).assertIsDisplayed()
    assertNoProfileData()

    compose.runOnIdle { state.value = ProfileUiState.Content("Bob Lee", "bob@epfl.ch") }
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("Bob Lee")
    compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals("bob@epfl.ch")
    compose.onNodeWithTag(ProfileTestTags.INITIALS).assertTextEquals("BL")
    compose.onNodeWithText(fullName).assertDoesNotExist()
    compose.onNodeWithText(email).assertDoesNotExist()

    compose.runOnIdle { state.value = ProfileUiState.Content(null, null) }
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("—")
    compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals("—")
    compose.onNodeWithTag(ProfileTestTags.NEUTRAL_AVATAR).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTestTags.INITIALS).assertDoesNotExist()
    compose.onNodeWithText("Bob Lee").assertDoesNotExist()
    compose.onNodeWithText("bob@epfl.ch").assertDoesNotExist()

    compose.runOnIdle { state.value = ProfileUiState.MissingUser }
    compose.onNodeWithTag(ProfileTestTags.MISSING_USER).assertIsDisplayed()
    assertNoProfileData()
  }

  @Test
  fun backAndSignOutInvokeOnlyTheirOwnCallbacks() {
    var backCalls = 0
    var signOutCalls = 0
    show(profile, onBack = { backCalls++ }, onSignOut = { signOutCalls++ })

    compose.runOnIdle {
      assertEquals(0, backCalls)
      assertEquals(0, signOutCalls)
    }
    compose.onNodeWithTag(NavigationTestTags.BACK_BUTTON).assertHasClickAction().performClick()
    compose.runOnIdle {
      assertEquals(1, backCalls)
      assertEquals(0, signOutCalls)
    }
    compose.onNodeWithTag(NavigationTestTags.SIGN_OUT_BUTTON).assertHasClickAction().performClick()
    compose.runOnIdle {
      assertEquals(1, backCalls)
      assertEquals(1, signOutCalls)
    }
  }

  @Test
  fun signOutErrorKeepsTheProfileVisibleAndAllowsRetry() {
    val error =
        mutableStateOf<AuthException?>(
            AuthException.Network(IllegalStateException("private provider details"))
        )
    var signOutCalls = 0
    compose.setContent {
      SampleAppTheme {
        ProfileScreen(
            uiState = profile,
            onBack = {},
            onSignOut = { signOutCalls++ },
            signOutError = error.value,
        )
      }
    }

    compose
        .onNodeWithTag(NavigationTestTags.SIGN_OUT_ERROR)
        .assertIsDisplayed()
        .assertTextEquals(compose.activity.getString(R.string.nav_sign_out_error))
    compose.onNodeWithText("private provider details").assertDoesNotExist()
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals(fullName)
    compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals(email)
    compose.onNodeWithTag(NavigationTestTags.SIGN_OUT_BUTTON).performClick()
    compose.runOnIdle { assertEquals(1, signOutCalls) }

    compose.runOnIdle { error.value = null }
    compose.onNodeWithTag(NavigationTestTags.SIGN_OUT_ERROR).assertDoesNotExist()
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals(fullName)
  }

  private fun assertNoProfileData() {
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertDoesNotExist()
    compose.onNodeWithTag(ProfileTestTags.EMAIL).assertDoesNotExist()
    compose.onNodeWithTag(ProfileTestTags.INITIALS).assertDoesNotExist()
    compose.onNodeWithTag(ProfileTestTags.NEUTRAL_AVATAR).assertDoesNotExist()
    compose.onNodeWithText(fullName).assertDoesNotExist()
    compose.onNodeWithText(email).assertDoesNotExist()
    compose.onNodeWithTag(ProfileTestTags.EDIT).assertDoesNotExist()
    compose.onNodeWithTag(ProfileTestTags.ADD_PHOTO).assertDoesNotExist()
  }

  private fun saveScreenshot() {
    val image = compose.onRoot().captureToImage().asAndroidBitmap()
    val file = File("build/reports/profile/profile.png")
    file.parentFile?.mkdirs()
    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
