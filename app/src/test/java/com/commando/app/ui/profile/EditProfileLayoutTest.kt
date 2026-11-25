package com.commando.app.ui.profile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.commando.app.model.authentication.AuthException
import com.commando.app.ui.theme.SampleAppTheme
import org.junit.Assert.assertEquals
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

  @Test
  fun pendingAndRejectedPublicWriteKeepConfirmedNameVisible() {
    val state = mutableStateOf(PublicNameEditState(true, "Confirmed", "Draft"))
    compose.setContent {
      SampleAppTheme {
        PublicNameEditor(
            state.value,
            {},
            { state.value = state.value.copy(draft = it) },
            { state.value = state.value.copy(busy = true) },
        )
      }
    }
    compose.onNodeWithText("Update public name").performClick()
    compose
        .onNodeWithTag(ProfileEditTestTags.PUBLIC_CONFIRMED)
        .assertTextEquals("Published name: Confirmed")
    compose.onNodeWithTag(ProfileEditTestTags.PUBLIC_NAME).assertIsNotEnabled()
    compose.runOnIdle { state.value = state.value.copy(busy = false, failed = true) }
    compose
        .onNodeWithTag(ProfileEditTestTags.PUBLIC_CONFIRMED)
        .assertTextEquals("Published name: Confirmed")
    compose.onNodeWithTag(ProfileEditTestTags.PUBLIC_NAME).assertTextEquals("Draft")
    compose.onNodeWithText("Change not confirmed. Try again.").assertIsDisplayed()
    compose.onNodeWithText("Update public name").assertIsEnabled().performClick()
    compose.onNodeWithTag(ProfileEditTestTags.PUBLIC_NAME).assertIsNotEnabled()
    compose
        .onNodeWithTag(ProfileEditTestTags.PUBLIC_CONFIRMED)
        .assertTextEquals("Published name: Confirmed")
  }

  @Test
  fun invalidEditedPublicInputShowsValidationAndBlocksPublication() {
    val state = mutableStateOf(PublicNameEditState(loaded = true))
    compose.setContent {
      SampleAppTheme {
        PublicNameEditor(
            state.value,
            {},
            { state.value = state.value.copy(draft = it) },
            { error("Invalid public name must not be submitted") },
        )
      }
    }
    compose.onNodeWithTag(ProfileEditTestTags.PUBLIC_NAME).performTextInput(" ")
    compose.onNodeWithTag(ProfileEditTestTags.PUBLIC_NAME).performTextClearance()
    compose
        .onNodeWithText(
            "Use 1\u201380 characters on one line and include at least one non-space character."
        )
        .assertIsDisplayed()
    compose.onNodeWithText("Publish name").assertIsNotEnabled()
    compose
        .onNodeWithTag(ProfileEditTestTags.PUBLIC_CONFIRMED)
        .assertTextEquals("No public name published.")
  }

  @Test
  fun publicReadErrorOffersRetryAndRecoversToUnpublishedState() {
    val state = mutableStateOf(PublicNameEditState(failed = true))
    var retries = 0
    compose.setContent {
      SampleAppTheme {
        PublicNameEditor(
            state.value,
            {
              retries++
              state.value = PublicNameEditState(loaded = true)
            },
            {},
            {},
        )
      }
    }
    compose.onNodeWithText("Couldn\u2019t load your public name.").assertIsDisplayed()
    compose.onNodeWithTag(ProfileEditTestTags.PUBLIC_NAME).assertDoesNotExist()
    compose.onNodeWithText("Retry").performClick()
    compose.runOnIdle { assertEquals(1, retries) }
    compose
        .onNodeWithTag(ProfileEditTestTags.PUBLIC_CONFIRMED)
        .assertTextEquals("No public name published.")
    compose.onNodeWithText("Couldn\u2019t load your public name.").assertDoesNotExist()
  }
}
