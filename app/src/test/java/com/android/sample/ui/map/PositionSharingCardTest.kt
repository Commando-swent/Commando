package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.android.sample.R
import com.android.sample.ui.home.HomeScreen
import com.android.sample.ui.home.HomeTripUiState
import com.android.sample.ui.navigation.AppMode
import com.android.sample.ui.theme.SampleAppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Sharing stays visible and retryable across Home loading and failure states. */
@RunWith(RobolectricTestRunner::class)
class PositionSharingCardTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun failedDeletionStaysCheckedAndAllowsTheUserToRetryStopping() {
    var state: PositionSharingState by mutableStateOf(PositionSharingState.Off)
    var requested: Boolean? = null
    compose.setContent { SampleAppTheme { PositionSharingCard(state, { requested = it }) } }
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).assertIsOff().performClick()
    compose.runOnIdle {
      assertEquals(true, requested)
      state = PositionSharingState.Live
    }
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).assertIsOn()
    compose.runOnIdle { state = PositionSharingState.Failed(SharingFailureReason.STOP) }
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).assertIsOn().performClick()
    compose.runOnIdle { assertEquals(false, requested) }
  }

  @Test
  fun sharingFailuresRemainVisibleAndStoppingPreventsRepeatedTaps() {
    var state: PositionSharingState by mutableStateOf(PositionSharingState.Starting)
    var text = ""
    compose.setContent {
      text = stringResource(R.string.position_sharing_starting)
      SampleAppTheme { PositionSharingCard(state, {}) }
    }
    compose.onNodeWithText(text).assertExists()
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).assertIsOn()
    compose.runOnIdle { state = PositionSharingState.Stopping }
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).assertIsNotEnabled()
    val messages =
        mapOf(
            SharingFailureReason.PERMISSION to R.string.position_sharing_permission,
            SharingFailureReason.LOCATION to R.string.position_sharing_location,
            SharingFailureReason.PUBLISH to R.string.position_sharing_publish,
            SharingFailureReason.NO_TRIP to R.string.position_sharing_no_trip,
        )
    for ((reason, resource) in messages) {
      compose.runOnIdle { state = PositionSharingState.Failed(reason) }
      val expected =
          androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
              .targetContext
              .getString(resource)
      compose.onNodeWithText(expected).assertExists()
      compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).assertIsOff().assertIsEnabled()
    }
  }

  @Test
  fun stopRemainsReachableWhenTheHomeTripDisappearsOrFailsToLoad() {
    var home: HomeTripUiState by mutableStateOf(HomeTripUiState.Empty)
    var stopped = false
    compose.setContent {
      SampleAppTheme {
        HomeScreen(
            AppMode.Commando,
            tripState = home,
            sharingControl = {
              PositionSharingCard(
                  PositionSharingState.Failed(SharingFailureReason.STOP),
                  { stopped = !it },
              )
            },
        )
      }
    }
    compose
        .onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE)
        .performScrollTo()
        .assertIsOn()
        .performClick()
    compose.runOnIdle {
      assertTrue(stopped)
      home = HomeTripUiState.Loading
    }
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).performScrollTo().assertIsOn()
  }
}
