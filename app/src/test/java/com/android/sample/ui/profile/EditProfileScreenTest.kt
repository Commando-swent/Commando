package com.android.sample.ui.profile

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.R
import com.android.sample.model.authentication.ProfileValidation
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
class EditProfileScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val initial = ProfileEditUiState(fullName = "Alex Morgan", email = "alex@example.com")

  private fun show(
      state: ProfileEditUiState = initial,
      onSave: () -> Unit = {},
      onCancel: () -> Unit = {},
  ) {
    compose.setContent { SampleAppTheme { EditProfileScreen(state, {}, {}, onSave, onCancel) } }
  }

  @Test
  fun initialFieldsArePrefilledAndValidSaveInvokesOnlySave() {
    var saves = 0
    var cancels = 0
    show(onSave = { saves++ }, onCancel = { cancels++ })

    compose.onNodeWithTag(ProfileEditTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).assertTextEquals("Alex Morgan")
    compose.onNodeWithTag(ProfileEditTestTags.EMAIL).assertTextEquals("alex@example.com")
    compose.onNodeWithTag(ProfileEditTestTags.INITIALS).assertTextEquals("AM")
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME_ERROR).assertDoesNotExist()
    compose.onNodeWithTag(ProfileEditTestTags.EMAIL_ERROR).assertDoesNotExist()
    compose.onNodeWithTag(ProfileEditTestTags.SAVE).assertIsEnabled().performClick()
    compose.runOnIdle {
      assertEquals(1, saves)
      assertEquals(0, cancels)
    }
    screenshot("edit-profile.png")
  }

  @Test
  fun clearingNameShowsRequiredErrorAndKeepsConfirmedInitials() {
    val state = mutableStateOf(initial)
    compose.setContent {
      SampleAppTheme {
        EditProfileScreen(
            uiState = state.value,
            onFullNameChange = {
              state.value = state.value.copy(fullName = it, fullNameError = it.isBlank())
            },
            onEmailChange = {},
            onSave = {},
            onCancel = {},
            avatarFullName = initial.fullName,
        )
      }
    }
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).performTextReplacement("")

    val required = compose.activity.getString(R.string.profile_edit_name_required)
    compose
        .onNodeWithTag(ProfileEditTestTags.FULL_NAME_ERROR)
        .assertIsDisplayed()
        .assertTextEquals(required)
    compose
        .onNodeWithTag(ProfileEditTestTags.FULL_NAME)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, required))
    compose.onNodeWithTag(ProfileEditTestTags.SAVE).assertIsNotEnabled()
    compose.onNodeWithTag(ProfileEditTestTags.INITIALS).assertTextEquals("AM")
    screenshot("edit-profile-required.png")

    compose
        .onNodeWithTag(ProfileEditTestTags.FULL_NAME)
        .performTextReplacement("Alex Morgan Updated")
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME_ERROR).assertDoesNotExist()
    compose.onNodeWithTag(ProfileEditTestTags.SAVE).assertIsEnabled()
  }

  @Test
  fun emptyEmailShowsEmailAddressPlaceholderAndPreventsSave() {
    show(initial.copy(email = ""))

    compose
        .onNodeWithText(compose.activity.getString(R.string.profile_edit_email_hint))
        .assertIsDisplayed()
    compose
        .onNodeWithTag(ProfileEditTestTags.EMAIL_ERROR)
        .assertTextEquals(compose.activity.getString(R.string.profile_edit_email_required))
    compose.onNodeWithTag(ProfileEditTestTags.SAVE).assertIsNotEnabled()
  }

  @Test
  fun invalidEmailDisplaysErrorAndTypingAValidAddressEnablesSave() {
    val state = mutableStateOf(initial)
    compose.setContent {
      SampleAppTheme {
        EditProfileScreen(
            uiState = state.value,
            onFullNameChange = {},
            onEmailChange = {
              state.value =
                  state.value.copy(email = it, emailError = !ProfileValidation.isValidEmail(it))
            },
            onSave = {},
            onCancel = {},
        )
      }
    }
    compose.onNodeWithTag(ProfileEditTestTags.EMAIL).performTextReplacement("not-an-email")
    compose
        .onNodeWithTag(ProfileEditTestTags.EMAIL_ERROR)
        .assertTextEquals(compose.activity.getString(R.string.profile_edit_email_invalid))
    compose.onNodeWithTag(ProfileEditTestTags.SAVE).assertIsNotEnabled()

    compose
        .onNodeWithTag(ProfileEditTestTags.EMAIL)
        .performTextReplacement("alex.updated@example.com")
    compose.onNodeWithTag(ProfileEditTestTags.EMAIL).assertTextEquals("alex.updated@example.com")
    compose.onNodeWithTag(ProfileEditTestTags.EMAIL_ERROR).assertDoesNotExist()
    compose.onNodeWithTag(ProfileEditTestTags.SAVE).assertIsEnabled()
  }

  @Test
  fun cancelAndBackDoNotSaveTheDraft() {
    var cancels = 0
    var saves = 0
    show(initial.copy(fullName = "Unsaved Name"), onSave = { saves++ }, onCancel = { cancels++ })

    compose.onNodeWithTag(ProfileEditTestTags.CANCEL).performClick()
    compose.onNodeWithTag(ProfileEditTestTags.BACK).performClick()
    compose.runOnIdle {
      assertEquals(2, cancels)
      assertEquals(0, saves)
    }
  }

  @Test
  fun savingShowsProgressAndDisablesFieldsAndNavigation() {
    show(initial.copy(isSaving = true))

    compose.onNodeWithTag(ProfileEditTestTags.SAVING).assertIsDisplayed()
    for (tag in
        listOf(
            ProfileEditTestTags.SAVE,
            ProfileEditTestTags.FULL_NAME,
            ProfileEditTestTags.EMAIL,
            ProfileEditTestTags.CANCEL,
            ProfileEditTestTags.BACK,
        )) {
      compose.onNodeWithTag(tag).assertIsNotEnabled()
    }
  }

  @Test
  fun errorsProvideSafeFeedbackWithoutDiscardingDraftAndAllowRetry() {
    val state = mutableStateOf(initial)
    compose.setContent { SampleAppTheme { EditProfileScreen(state.value, {}, {}, {}, {}) } }
    val messages =
        mapOf(
            ProfileSaveError.NETWORK to R.string.profile_edit_network_error,
            ProfileSaveError.EMAIL_IN_USE to R.string.profile_edit_email_in_use,
            ProfileSaveError.RECENT_LOGIN_REQUIRED to R.string.profile_edit_recent_login,
            ProfileSaveError.SESSION_CHANGED to R.string.profile_edit_session_changed,
            ProfileSaveError.UNKNOWN to R.string.profile_edit_unknown_error,
        )
    for ((error, message) in messages) {
      compose.runOnIdle { state.value = initial.copy(error = error) }
      compose
          .onNodeWithTag(ProfileEditTestTags.FEEDBACK)
          .assertTextEquals(compose.activity.getString(message))
      compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).assertTextEquals(initial.fullName)
      compose.onNodeWithTag(ProfileEditTestTags.EMAIL).assertTextEquals(initial.email)
      compose.onNodeWithTag(ProfileEditTestTags.SAVE).assertIsEnabled()
    }
  }

  @Test
  fun pendingEmailExplainsVerificationAndDoneReturnsWithoutAnotherSave() {
    var cancels = 0
    var saves = 0
    val newEmail = "alex.new@example.com"
    show(
        initial.copy(email = newEmail, pendingEmail = newEmail, saved = true),
        onSave = { saves++ },
        onCancel = { cancels++ },
    )

    compose
        .onNodeWithTag(ProfileEditTestTags.FEEDBACK)
        .assertTextEquals(
            compose.activity.getString(R.string.profile_edit_email_verification, newEmail)
        )
    compose.onNodeWithTag(ProfileEditTestTags.SAVE).assertDoesNotExist()
    compose.onNodeWithTag(ProfileEditTestTags.EMAIL).assertIsNotEnabled()
    compose.onNodeWithTag(ProfileEditTestTags.DONE).performClick()
    compose.runOnIdle {
      assertEquals(1, cancels)
      assertEquals(0, saves)
    }
  }

  @Test
  fun confirmedNameOnlySaveShowsSuccessAndDone() {
    show(initial.copy(saved = true))

    compose
        .onNodeWithTag(ProfileEditTestTags.FEEDBACK)
        .assertTextEquals(compose.activity.getString(R.string.profile_edit_saved))
    compose.onNodeWithTag(ProfileEditTestTags.DONE).assertIsDisplayed()
    compose.onNodeWithTag(ProfileEditTestTags.SAVE).assertDoesNotExist()
  }

  @Test
  fun unavailableAvatarNameUsesNeutralIcon() {
    compose.setContent {
      SampleAppTheme { EditProfileScreen(initial, {}, {}, {}, {}, avatarFullName = null) }
    }
    compose.onNodeWithTag(ProfileEditTestTags.INITIALS).assertDoesNotExist()
    compose
        .onNodeWithContentDescription(compose.activity.getString(R.string.profile_neutral_avatar))
        .assertIsDisplayed()
  }

  @Test
  fun keyboardDoneSavesValidFormAndNextMovesBetweenFields() {
    var saves = 0
    show(onSave = { saves++ })
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).performImeAction()
    compose.onNodeWithTag(ProfileEditTestTags.EMAIL).performImeAction()
    compose.runOnIdle { assertEquals(1, saves) }
  }

  @Test
  fun keyboardDoneCannotSubmitInvalidDraft() {
    var saves = 0
    show(initial.copy(fullName = " ", fullNameError = true), onSave = { saves++ })
    compose.onNodeWithTag(ProfileEditTestTags.EMAIL).performImeAction()
    compose.runOnIdle { assertEquals(0, saves) }
  }

  private fun screenshot(name: String) {
    val image = compose.onRoot().captureToImage().asAndroidBitmap()
    val file = File("build/reports/profile/$name")
    file.parentFile?.mkdirs()
    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
