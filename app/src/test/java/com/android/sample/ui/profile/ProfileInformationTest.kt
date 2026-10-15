package com.android.sample.ui.profile

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.ui.unit.dp
import com.android.sample.data.repository.ProfilePhotoRepository
import com.android.sample.model.ProfilePhotoSource
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.theme.SampleAppTheme
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w412dp-h830dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileInformationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val profile = mutableStateOf(ProfileUiState.Content("Alice Martin", "alice@epfl.ch"))

  @Test
  fun plusOpensCameraAndGalleryAndRemovalRestoresInitials() {
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
    val image = output.toByteArray()
    var stored: ByteArray? = null
    val photos =
        object : ProfilePhotoRepository {
          override suspend fun load(userId: String) = stored

          override suspend fun save(userId: String, source: ProfilePhotoSource?) =
              (if (source == null) null else image).also { stored = it }
        }
    fun choose(label: String): Intent {
      compose.onNodeWithTag(ProfileTestTags.ADD_PHOTO).performClick()
      compose.onNodeWithText(label).performClick()
      return shadowOf(compose.activity).nextStartedActivityForResult.intent
    }
    val model = ProfileViewModel(FakeAuthRepository(AuthUser("alice", "Alice Martin")), photos)
    compose.setContent {
      SampleAppTheme {
        val (photo, onPhotoClick) = ProfilePhotoActions(model)
        ProfileInformation(profile.value, photo = photo, onPhotoClick = onPhotoClick)
      }
    }
    compose.onNodeWithTag(ProfileTestTags.INITIALS).performClick()
    compose.onNodeWithTag(ProfilePhotoTestTags.PREVIEW).assertIsDisplayed()
    compose
        .onNode(
            hasTestTag(ProfileTestTags.INITIALS) and
                hasAnyAncestor(hasTestTag(ProfilePhotoTestTags.PREVIEW))
        )
        .assertTextEquals("AM")
    compose.onNodeWithTag(ProfilePhotoTestTags.CLOSE).performClick()
    compose.onNodeWithTag(ProfilePhotoTestTags.PREVIEW).assertDoesNotExist()
    compose.onNodeWithTag(ProfileTestTags.ADD_PHOTO).assertIsEnabled().performClick()
    compose.onNodeWithTag(ProfilePhotoTestTags.PREVIEW).assertDoesNotExist()
    compose.onNodeWithText("Choose from gallery").assertIsDisplayed()
    compose.onNodeWithText("Remove photo").assertIsNotEnabled()
    compose.onNodeWithText("Cancel").performClick()
    compose.onNodeWithTag(ProfileTestTags.INITIALS).assertTextEquals("AM")
    val retryCamera = choose("Take a photo")
    compose.runOnIdle {
      shadowOf(compose.activity)
          .receiveResult(retryCamera, Activity.RESULT_OK, Intent().putExtra("data", bitmap))
    }
    compose.onNodeWithTag(ProfilePhotoTestTags.IMAGE).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTestTags.INITIALS).assertDoesNotExist()
    compose.onNodeWithTag(ProfilePhotoTestTags.IMAGE).performClick()
    compose
        .onNode(
            hasTestTag(ProfilePhotoTestTags.IMAGE) and
                hasAnyAncestor(hasTestTag(ProfilePhotoTestTags.PREVIEW))
        )
        .assertIsDisplayed()
    compose.onNodeWithTag(ProfilePhotoTestTags.CLOSE).performClick()
    for (label in listOf("Take a photo", "Choose from gallery")) {
      val cancelled = choose(label)
      compose.runOnIdle {
        shadowOf(compose.activity).receiveResult(cancelled, Activity.RESULT_CANCELED, null)
      }
      compose.onNodeWithTag(ProfilePhotoTestTags.IMAGE).assertIsDisplayed()
    }
    val selection = choose("Choose from gallery")
    val data = Intent().setData(Uri.parse("content://photos/selection"))
    compose.runOnIdle {
      shadowOf(compose.activity).receiveResult(selection, Activity.RESULT_OK, data)
    }
    compose.onNodeWithTag(ProfilePhotoTestTags.IMAGE).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTestTags.ADD_PHOTO).performClick()
    compose.onNodeWithText("Remove photo").performClick()
    compose.onNodeWithTag(ProfilePhotoTestTags.IMAGE).assertDoesNotExist()
    compose.onNodeWithTag(ProfileTestTags.INITIALS).assertTextEquals("AM")
  }

  @Test
  fun savingPhotoDisablesTheBadge() {
    compose.setContent {
      SampleAppTheme {
        ProfileInformation(profile.value, photo = PhotoUiState(busy = true), onPhotoClick = {})
      }
    }
    compose.onNodeWithTag(ProfileTestTags.ADD_PHOTO).assertIsNotEnabled()
  }

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
  fun avatarSupportsTheEnlargedLayoutWithoutTheAddBadge() {
    compose.setContent {
      SampleAppTheme { ProfileAvatar("AM", showPhotoAction = false, diameter = 240.dp) }
    }
    compose
        .onNodeWithTag(ProfileTestTags.INITIALS)
        .assertTextEquals("AM")
        .assertHeightIsAtLeast(70.dp)
    compose.onNodeWithTag(ProfileTestTags.ADD_PHOTO).assertDoesNotExist()
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
