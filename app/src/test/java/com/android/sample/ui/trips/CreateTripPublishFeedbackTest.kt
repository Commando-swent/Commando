package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sample.data.repository.TripError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateTripPublishFeedbackTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun publishBar_followsThePublicationStates() {
    var state by mutableStateOf(CreateTripUiState())
    var clicks = 0
    composeTestRule.setContent { PublishBar(state) { clicks++ } }
    val button = composeTestRule.onNodeWithTag(CreateTripScreenTestTags.PUBLISH_BUTTON)
    // The spinner sits inside the button, so it only exists in the unmerged tree.
    val progress =
        composeTestRule.onNodeWithTag(
            CreateTripScreenTestTags.PUBLISH_PROGRESS,
            useUnmergedTree = true,
        )

    button.assertIsNotEnabled().assertTextContains("Publish trip").performClick()
    progress.assertDoesNotExist()

    state = CreateTripUiState(canPublish = true)
    button.assertIsEnabled().performClick()

    state = CreateTripUiState(isPublishing = true)
    button.assertIsNotEnabled().assertTextContains("Publishing…").performClick()
    progress.assertExists()

    state = CreateTripUiState(canPublish = true, publishError = TripError.NetworkError)
    button.assertIsEnabled().assertTextContains("Try again").performClick()
    progress.assertDoesNotExist()

    composeTestRule.runOnIdle { assertEquals(2, clicks) }
  }

  @Test
  fun errorBanner_explainsEachError() {
    val messages =
        mapOf(
            TripError.NetworkError to
                "Check your connection and try again. Your details are saved.",
            TripError.PermissionDenied to
                "You are not allowed to publish trips. Your details are saved.",
            TripError.InvalidData to "Some details were not accepted. Check them and try again.",
            TripError.NotFound to "Something went wrong. Try again. Your details are saved.",
            TripError.Unknown to "Something went wrong. Try again. Your details are saved.",
        )
    var error by mutableStateOf<TripError>(TripError.NetworkError)
    composeTestRule.setContent { PublishErrorBanner(error) }

    messages.forEach { (shown, message) ->
      error = shown
      composeTestRule.onNodeWithText("Couldn't publish your trip").assertExists()
      composeTestRule.onNodeWithText(message).assertExists()
    }
  }
}
