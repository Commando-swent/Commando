package com.android.sample.ui.profile

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.ui.theme.SampleAppTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w412dp-h830dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileInformationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val profile = mutableStateOf(ProfileUiState.Content("Alice Martin", "alice@epfl.ch"))

  private fun show() {
    compose.setContent {
      SampleAppTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
          Box { ProfileInformation(profile.value, Modifier.testTag("profile_information")) }
        }
      }
    }
  }

  @Test
  fun actualValuesInitialsAndDisabledControlsAreDisplayed() {
    profile.value = ProfileUiState.Content("  Élodie van der Meer  ", "Elodie+work@epfl.ch")
    show()
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("  Élodie van der Meer  ")
    compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals("Elodie+work@epfl.ch")
    compose.onNodeWithTag(ProfileTestTags.INITIALS).assertTextEquals("ÉM")
    compose.onNodeWithTag(ProfileTestTags.NEUTRAL_AVATAR).assertDoesNotExist()
    compose.onNodeWithTag(ProfileTestTags.EDIT).assertIsDisplayed().assertIsNotEnabled()
    compose
        .onNodeWithTag(ProfileTestTags.ADD_PHOTO)
        .assertIsDisplayed()
        .assertIsNotEnabled()
        .assertContentDescriptionEquals("Add profile photo")
    val file = File("build/reports/profile/profile-components.png")
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
  fun sectionsAreStackedInsideBoxAndCallerModifierIsApplied() {
    show()
    compose.onNodeWithTag("profile_information").assertIsDisplayed()
    val avatar = compose.onNodeWithTag(ProfileTestTags.INITIALS).fetchSemanticsNode().boundsInRoot
    val edit = compose.onNodeWithTag(ProfileTestTags.EDIT).fetchSemanticsNode().boundsInRoot
    val name = compose.onNodeWithTag(ProfileTestTags.FULL_NAME).fetchSemanticsNode().boundsInRoot
    org.junit.Assert.assertTrue(avatar.bottom <= edit.top)
    org.junit.Assert.assertTrue(edit.bottom <= name.top)
  }

  @Test
  fun missingNameOrEmailUsesFallbackWithoutRetainingOldValues() {
    show()
    for (missing in listOf(null, "", " \t\n ")) {
      compose.runOnIdle { profile.value = ProfileUiState.Content(missing, "alice@epfl.ch") }
      compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("—")
      compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals("alice@epfl.ch")
      compose.onNodeWithTag(ProfileTestTags.NEUTRAL_AVATAR).assertIsDisplayed()
      compose.onNodeWithTag(ProfileTestTags.INITIALS).assertDoesNotExist()
      compose.onNodeWithText("Alice Martin").assertDoesNotExist()
      compose.runOnIdle { profile.value = ProfileUiState.Content("Alice Martin", missing) }
      compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("Alice Martin")
      compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals("—")
      compose.onNodeWithTag(ProfileTestTags.INITIALS).assertTextEquals("AM")
      compose.onNodeWithTag(ProfileTestTags.NEUTRAL_AVATAR).assertDoesNotExist()
      compose.onNodeWithText("alice@epfl.ch").assertDoesNotExist()
    }
  }

  @Test
  fun missingFieldsAndSingleWordNameNeverGenerateFictionalInitials() {
    profile.value = ProfileUiState.Content(null, null)
    show()
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("—")
    compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals("—")
    compose.onNodeWithTag(ProfileTestTags.NEUTRAL_AVATAR).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTestTags.INITIALS).assertDoesNotExist()
    compose.runOnIdle { profile.value = ProfileUiState.Content("yasmine", "yasmine@epfl.ch") }
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("yasmine")
    compose.onNodeWithTag(ProfileTestTags.INITIALS).assertTextEquals("Y")
    compose.onNodeWithTag(ProfileTestTags.NEUTRAL_AVATAR).assertDoesNotExist()
  }
}
