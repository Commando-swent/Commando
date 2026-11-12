package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sample.R
import com.android.sample.model.CommandoPosition
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** All tracking outcomes retain useful feedback without inventing a marker. */
@RunWith(RobolectricTestRunner::class)
class OrderTrackingScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun trackingFeedbackAndMarkerFollowTheCurrentStateAndRetryRemainsReachable() {
    val fix = CommandoPosition(46.521, 6.566, 30.0, Instant.now())
    var state: OrderTrackingState by mutableStateOf(OrderTrackingState.Loading)
    var retries = 0
    compose.setContent {
      SampleAppTheme {
        TripMapScreen(
            TripMapUiState.Content(emptyList()),
            { retries++ },
            {},
            {},
            {},
            {},
            tracking = state,
            mapSurface = { Box(it) },
        )
      }
    }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val cases =
        listOf(
            OrderTrackingState.Loading to R.string.order_tracking_loading,
            OrderTrackingState.Unavailable to R.string.order_tracking_error,
            OrderTrackingState.Position(null) to R.string.order_tracking_unavailable,
            OrderTrackingState.Position(fix.copy(latitude = 0.0)) to
                R.string.order_tracking_off_map,
            OrderTrackingState.Position(fix) to R.string.order_tracking_live,
        )
    for ((next, message) in cases) {
      compose.runOnIdle { state = next }
      compose.onAllNodesWithText(context.getString(message)).onFirst().assertExists()
      compose.onNodeWithTag(TripMapTestTags.LOCATE).performClick()
      if (next == OrderTrackingState.Position(fix))
          compose.onNodeWithTag(OrderTrackingTestTags.MARKER).assertExists()
      else compose.onNodeWithTag(OrderTrackingTestTags.MARKER).assertDoesNotExist()
    }
    compose.onNodeWithTag(TripMapTestTags.REFRESH).performClick()
    compose.runOnIdle { assertEquals(1, retries) }
  }
}
