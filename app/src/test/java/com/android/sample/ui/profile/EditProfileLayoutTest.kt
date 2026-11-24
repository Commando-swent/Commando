package com.android.sample.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.android.sample.R
import com.android.sample.model.authentication.AuthException
import com.android.sample.ui.theme.SampleAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EditProfileLayoutTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun typingAndRecompositionDoNotPublishUntilPublishIsClicked() {
    val editor = mutableStateOf<PublicNameEditState?>(null)
    var loads = 0
    val writes = mutableListOf<String?>()
    lateinit var manage: String
    lateinit var publish: String
    compose.setContent {
      manage = stringResource(R.string.public_name_manage)
      publish = stringResource(R.string.public_name_publish)
      SampleAppTheme {
        Column {
          PublicNameEditor(
              editor.value,
              onLoad = {
                loads++
                editor.value = PublicNameEditState(loaded = true)
              },
              onEdit = { editor.value = editor.value?.copy(draft = it) },
              onSave = { writes.add(it) },
          )
        }
      }
    }
    compose.onNodeWithText(manage).performClick()
    compose.onNodeWithTag(ProfileEditTestTags.PUBLIC_NAME).performTextInput("Public alias")
    compose.runOnIdle {
      assertEquals(1, loads)
      assertEquals(emptyList<String?>(), writes)
    }
    compose.onNodeWithText(publish).performClick()
    compose.runOnIdle { assertEquals(listOf("Public alias"), writes) }
  }

  @Test
  fun whitespaceOnlyPublicNameShowsFieldErrorAndDisablesPublication() {
    val state = mutableStateOf(PublicNameEditState(loaded = true))
    lateinit var error: String
    lateinit var publish: String
    compose.setContent {
      error = stringResource(R.string.public_name_invalid)
      publish = stringResource(R.string.public_name_publish)
      SampleAppTheme {
        Column {
          PublicNameEditor(state.value, {}, { state.value = state.value.copy(draft = it) }, {})
        }
      }
    }
    compose.onNodeWithText(error).assertDoesNotExist()
    compose.onNodeWithTag(ProfileEditTestTags.PUBLIC_NAME).performTextInput("\u00A0")
    compose.onNodeWithText(error).assertIsDisplayed()
    compose.onNodeWithText(publish).assertIsNotEnabled()
  }

  @Test
  fun publicLoadFailureOffersRetryWithoutShowingAnUnpublishedField() {
    var retries = 0
    lateinit var error: String
    lateinit var retry: String
    compose.setContent {
      error = stringResource(R.string.public_name_load_failed)
      retry = stringResource(R.string.trips_retry)
      SampleAppTheme {
        Column { PublicNameEditor(PublicNameEditState(failed = true), { retries++ }, {}, {}) }
      }
    }
    compose.onNodeWithText(error).assertIsDisplayed()
    compose.onNodeWithTag(ProfileEditTestTags.PUBLIC_NAME).assertDoesNotExist()
    compose.onNodeWithText(retry).performClick()
    compose.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun publicUpdateAndRemovalUseExplicitActionsAndPreserveFailedDraft() {
    val state =
        mutableStateOf(
            PublicNameEditState(loaded = true, confirmed = "Old", draft = "New", failed = true)
        )
    val writes = mutableListOf<String?>()
    lateinit var update: String
    lateinit var remove: String
    lateinit var error: String
    compose.setContent {
      update = stringResource(R.string.public_name_update)
      remove = stringResource(R.string.public_name_remove)
      error = stringResource(R.string.public_name_write_failed)
      SampleAppTheme { Column { PublicNameEditor(state.value, {}, {}, { writes.add(it) }) } }
    }
    compose.onNodeWithText(error).assertIsDisplayed()
    compose.onNodeWithTag(ProfileEditTestTags.PUBLIC_NAME).assertTextEquals("New")
    compose.onNodeWithText(update).performClick()
    compose.onNodeWithText(remove).performClick()
    compose.runOnIdle {
      assertEquals(listOf("New", null), writes)
      state.value = state.value.copy(busy = true)
    }
    compose.onNodeWithText(update).assertIsNotEnabled()
    compose.onNodeWithText(remove).assertIsNotEnabled()
    compose.onNodeWithTag(ProfileEditTestTags.PUBLIC_NAME).assertIsNotEnabled()
  }

  @Test
  fun clearingPublicDraftShowsValidationWithoutOfferingPublication() {
    val state = mutableStateOf(PublicNameEditState(loaded = true, draft = "Alias"))
    lateinit var error: String
    compose.setContent {
      error = stringResource(R.string.public_name_invalid)
      SampleAppTheme {
        Column {
          PublicNameEditor(state.value, {}, { state.value = state.value.copy(draft = it) }, {})
        }
      }
    }
    compose.onNodeWithTag(ProfileEditTestTags.PUBLIC_NAME).performTextClearance()
    compose.onNodeWithText(error).assertIsDisplayed()
  }

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
