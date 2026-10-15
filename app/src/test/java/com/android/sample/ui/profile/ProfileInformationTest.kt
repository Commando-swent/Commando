package com.android.sample.ui.profile

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.android.sample.ui.navigation.AppMode
import com.android.sample.ui.navigation.AppScaffold
import com.android.sample.ui.navigation.AppTestTags
import com.android.sample.ui.navigation.CommandoScreens
import com.android.sample.ui.theme.SampleAppTheme
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertTrue
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

  private val bitmap by lazy { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) }
  private val image by lazy {
    ByteArrayOutputStream()
        .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        .toByteArray()
  }
  private var stored: ByteArray? = null
  private var failed = false

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  private fun errorMessage() = compose.onNodeWithText("Could not load or update your photo.")

  private fun capturePhotoState(name: String) {
    val file = File("build/reports/profile/$name.png")
    file.parentFile?.mkdirs()
    file.outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  private fun showPhotos(hasPhoto: Boolean = true, loadFails: Boolean = false) {
    stored = if (hasPhoto) image else null
    failed = loadFails
    val photos =
        object : ProfilePhotoRepository {
          override suspend fun load(userId: String) = stored.also { check(!failed) }

          override suspend fun save(userId: String, source: ProfilePhotoSource?) =
              (if (source == null) null else image).also {
                check(!failed)
                stored = it
              }
        }
    val model = ProfileViewModel(FakeAuthRepository(AuthUser("alice", "Alice Martin")), photos)
    compose.setContent {
      SampleAppTheme {
        val snackbar = remember { SnackbarHostState() }
        AppScaffold(
            CommandoScreens.Profile,
            AppMode.Requester,
            {},
            {},
            {},
            snackbarHost = { ProfilePhotoSnackbarHost(snackbar) },
        ) { padding ->
          val (photo, onPhotoClick) = ProfilePhotoActions(model, snackbar)
          ProfileScreen(
              profile.value,
              {},
              {},
              Modifier.padding(padding),
              photo = photo,
              onPhotoClick = onPhotoClick,
          )
        }
      }
    }
  }

  private fun preview(tag: String) {
    node(tag).performClick()
    val inPreview = hasAnyAncestor(hasTestTag(ProfileTestTags.PHOTO_PREVIEW))
    compose.onNode(hasTestTag(tag) and inPreview).assertIsDisplayed()
    node(ProfileTestTags.PHOTO_CLOSE).performClick()
    node(ProfileTestTags.PHOTO_PREVIEW).assertDoesNotExist()
  }

  private fun choose(label: String, data: Intent?, result: Int = Activity.RESULT_OK) {
    node(ProfileTestTags.ADD_PHOTO).performClick()
    compose.onNodeWithText(label).performClick()
    val intent = shadowOf(compose.activity).nextStartedActivityForResult.intent
    compose.runOnIdle { shadowOf(compose.activity).receiveResult(intent, result, data) }
  }

  private fun removePhoto() {
    node(ProfileTestTags.ADD_PHOTO).performClick()
    compose.onNodeWithText("Remove photo").assertIsEnabled().performClick()
  }

  @Test
  fun failedInitialLoadCanBeRetriedWithoutSigningOut() {
    showPhotos(loadFails = true)
    errorMessage().assertIsDisplayed()
    node(ProfileTestTags.INITIALS).assertIsDisplayed()
    val errorBounds = errorMessage().fetchSemanticsNode().boundsInRoot
    val bottomBar = node(AppTestTags.BOTTOM_BAR).fetchSemanticsNode().boundsInRoot
    assertTrue(errorBounds.bottom <= bottomBar.top)
    compose.runOnIdle { failed = false }
    compose.onNodeWithText("Reload photo").performClick()
    errorMessage().assertDoesNotExist()
    node(ProfileTestTags.PHOTO_IMAGE).assertIsDisplayed()
    node(ProfileTestTags.ADD_PHOTO).performClick()
    compose.onNodeWithText("Remove photo").assertIsEnabled()
    capturePhotoState("photo-options")
  }

  @Test
  fun failedRemovalPreservesThePhotoAndFeedbackCanBeDismissed() {
    showPhotos()
    compose.runOnIdle { failed = true }
    removePhoto()
    errorMessage().assertIsDisplayed()
    node(ProfileTestTags.PHOTO_IMAGE).assertIsDisplayed()
    compose.onNodeWithContentDescription("Dismiss").performClick()
    errorMessage().assertDoesNotExist()
    node(ProfileTestTags.PHOTO_IMAGE).assertIsDisplayed()
  }

  @Test
  fun photoErrorDismissesAutomatically() {
    showPhotos(loadFails = true)
    errorMessage().assertIsDisplayed()
    capturePhotoState("photo-error")
    compose.mainClock.advanceTimeBy(11_000)
    errorMessage().assertDoesNotExist()
  }

  @Test
  fun cancellingCameraOrGalleryPreservesTheConfirmedPhoto() {
    showPhotos()
    for (label in listOf("Take a photo", "Choose from gallery")) {
      choose(label, null, Activity.RESULT_CANCELED)
      node(ProfileTestTags.PHOTO_IMAGE).assertIsDisplayed()
    }
  }

  @Test
  fun selectedPhotosAreDisplayedAndRemovalRestoresInitials() {
    showPhotos(hasPhoto = false)
    node(ProfileTestTags.ADD_PHOTO).performClick()
    compose.onNodeWithText("Remove photo").assertIsNotEnabled()
    compose.onNodeWithText("Cancel").performClick()
    choose("Take a photo", Intent().putExtra("data", bitmap))
    node(ProfileTestTags.PHOTO_IMAGE).assertIsDisplayed()
    choose("Choose from gallery", Intent().setData(Uri.parse("content://photos/selection")))
    node(ProfileTestTags.PHOTO_IMAGE).assertIsDisplayed()
    removePhoto()
    node(ProfileTestTags.PHOTO_IMAGE).assertDoesNotExist()
    node(ProfileTestTags.INITIALS).assertTextEquals("AM")
  }

  @Test
  fun enlargedPreviewSupportsPhotosInitialsAndTheNeutralAvatar() {
    showPhotos()
    preview(ProfileTestTags.PHOTO_IMAGE)
    removePhoto()
    preview(ProfileTestTags.INITIALS)
    compose.runOnIdle { profile.value = profile.value.copy(fullName = null) }
    preview(ProfileTestTags.NEUTRAL_AVATAR)
  }

  @Test
  fun unreadableStoredPhotoFallsBackToInitials() {
    val image = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
    compose.setContent { ProfileInformation(profile.value, photo = PhotoUiState(image)) }
    node(ProfileTestTags.INITIALS).assertTextEquals("AM")
    node(ProfileTestTags.PHOTO_IMAGE).assertDoesNotExist()
  }

  @Test
  fun editingProfileDisablesPhotoActionsWhileAPhotoIsSaving() {
    val photo = mutableStateOf(PhotoUiState())
    compose.setContent {
      EditProfileScreen(
          ProfileEditUiState("Alice Martin", "alice@epfl.ch"),
          {},
          {},
          {},
          {},
          "Alice Martin",
          photo = photo.value,
          onPhotoClick = {},
      )
    }
    node(ProfileTestTags.ADD_PHOTO).assertIsEnabled()
    compose.runOnIdle { photo.value = PhotoUiState(busy = true) }
    node(ProfileTestTags.ADD_PHOTO).assertIsNotEnabled()
  }

  @Test
  fun savingPhotoDisablesTheBadge() {
    compose.setContent {
      ProfileInformation(profile.value, photo = PhotoUiState(busy = true), onPhotoClick = {})
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
