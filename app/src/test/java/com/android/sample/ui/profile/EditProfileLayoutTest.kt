package com.android.sample.ui.profile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.android.sample.model.authentication.AuthException
import com.android.sample.ui.theme.SampleAppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EditProfileLayoutTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun validationSavingAndProviderFailuresKeepTheFormRecoverable() {
    val state = mutableStateOf(ProfileEditUiState("", ""))
    compose.setContent {
      SampleAppTheme { EditProfileScreen(state.value, {}, {}, {}, {}, "Alice") }
    }
    compose.onNodeWithText("Please enter your full name.").assertIsDisplayed()
    compose.onNodeWithText("Please enter your email address.").assertIsDisplayed()
    compose.onNodeWithTag(ProfileEditTestTags.SAVE).assertIsNotEnabled()
    compose.runOnIdle {
      state.value = ProfileEditUiState("Alice", "alice@epfl.ch", isSaving = true)
    }
    compose.onNodeWithTag(ProfileEditTestTags.CANCEL).assertIsNotEnabled()
    for (error in
        listOf(
            AuthException.Network(),
            AuthException.EmailAlreadyInUse(),
            AuthException.AccountConflict(),
            AuthException.SessionChanged(),
            AuthException.Unknown(),
        )) {
      compose.runOnIdle { state.value = state.value.copy(isSaving = false, error = error) }
      compose.onNodeWithTag(ProfileEditTestTags.FEEDBACK).assertIsDisplayed()
      compose.onNodeWithTag(ProfileEditTestTags.CANCEL).assertIsEnabled()
    }
  }
}
