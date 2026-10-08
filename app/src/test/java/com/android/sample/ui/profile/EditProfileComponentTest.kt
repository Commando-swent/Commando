package com.android.sample.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.ui.theme.SampleAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EditProfileComponentTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun draftValidationSavingAndFeedbackRespectAccountState() {
    val valid = ProfileEditUiState(fullName = "Alice Martin", email = "alice@example.test")
    val draft = mutableStateOf(valid)
    var saves = 0
    var cancels = 0
    compose.setContent {
      SampleAppTheme { EditProfileScreen(draft.value, {}, {}, { saves++ }, { cancels++ }) }
    }
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).assertTextEquals(valid.fullName)
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).performImeAction()
    compose.onNodeWithTag(ProfileEditTestTags.EMAIL).performImeAction()
    compose.onNodeWithTag(ProfileEditTestTags.SAVE).performClick()
    compose.onNodeWithTag(ProfileEditTestTags.CANCEL).performClick()
    compose.onNodeWithTag(ProfileEditTestTags.BACK).performClick()
    compose.runOnIdle {
      assertEquals(2, saves)
      assertEquals(2, cancels)
    }
    for (invalid in
        listOf(
            valid.copy(fullName = "", fullNameError = true),
            valid.copy(email = ""),
            valid.copy(email = "bad", emailError = true),
        )) {
      compose.runOnIdle { draft.value = invalid }
      compose.onNodeWithTag(ProfileEditTestTags.SAVE).assertIsNotEnabled()
      compose.onNodeWithTag(ProfileEditTestTags.EMAIL).performImeAction()
    }
    compose.runOnIdle { draft.value = valid.copy(isSaving = true) }
    compose.onNodeWithTag(ProfileEditTestTags.SAVING).assertIsDisplayed()
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).assertIsNotEnabled()
    for (error in ProfileSaveError.entries) {
      compose.runOnIdle { draft.value = valid.copy(error = error) }
      compose.onNodeWithTag(ProfileEditTestTags.FEEDBACK).assertIsDisplayed()
      compose.onNodeWithTag(ProfileEditTestTags.SAVE).assertIsEnabled()
    }
    for (pending in listOf(null, "new@example.test")) {
      compose.runOnIdle { draft.value = valid.copy(saved = true, pendingEmail = pending) }
      compose.onNodeWithTag(ProfileEditTestTags.FEEDBACK).assertIsDisplayed()
      compose.onNodeWithTag(ProfileEditTestTags.DONE).performClick()
    }
    compose.runOnIdle { draft.value = valid.copy(fullName = "") }
    compose.onNodeWithTag(ProfileEditTestTags.INITIALS).assertDoesNotExist()
  }
}
