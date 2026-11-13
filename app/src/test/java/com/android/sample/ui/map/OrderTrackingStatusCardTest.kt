package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

/** Contextual actions, live metadata updates and compact/large-text scrolling. */
@RunWith(RobolectricTestRunner::class)
class OrderTrackingStatusCardTest {
  @get:Rule val compose = createComposeRule()
  private val now = Instant.parse("2026-11-13T10:00:00Z")
  private val fix = CommandoPosition(46.521, 6.566, 30.0, now.minusSeconds(15))
  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun liveCardShowsAgeAndAccuracyAndInvokesOnlyRecenter() {
    var centers = 0
    var retries = 0
    compose.setContent {
      SampleAppTheme {
        OrderTrackingStatusCard(
            orderTrackingPresentation(OrderTrackingState.Position(fix), now),
            { centers++ },
            { retries++ },
        )
      }
    }
    compose
        .onNodeWithTag(OrderTrackingStatusTags.AGE)
        .assertTextEquals(context.resources.getQuantityString(R.plurals.order_tracking_age, 15, 15))
    compose.onNodeWithText(context.getString(R.string.order_tracking_accuracy, 30)).assertExists()
    compose.onNodeWithTag(OrderTrackingStatusTags.RETRY).assertDoesNotExist()
    compose.onNodeWithTag(TripMapTestTags.LOCATE).performClick()
    compose.runOnIdle {
      assertEquals(1, centers)
      assertEquals(0, retries)
    }
  }

  @Test
  fun lostPositionClearsMetadataAndRetryInvokesOnlyRefresh() {
    var state: OrderTrackingState by mutableStateOf(OrderTrackingState.Position(fix))
    var retries = 0
    compose.setContent {
      SampleAppTheme {
        OrderTrackingStatusCard(
            orderTrackingPresentation(state, now),
            { error("Unexpected recenter") },
            { retries++ },
        )
      }
    }
    compose.runOnIdle { state = OrderTrackingState.Unavailable }
    compose.onNodeWithTag(OrderTrackingStatusTags.AGE).assertDoesNotExist()
    compose.onNodeWithTag(TripMapTestTags.LOCATE).assertDoesNotExist()
    compose.onNodeWithTag(OrderTrackingStatusTags.RETRY).performClick()
    compose.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun loadingNeverExposesAnActionOrOldCoordinates() {
    compose.setContent {
      SampleAppTheme {
        OrderTrackingStatusCard(orderTrackingPresentation(OrderTrackingState.Loading, now), {}, {})
      }
    }
    compose.onNodeWithTag(OrderTrackingStatusTags.AGE).assertDoesNotExist()
    compose.onNodeWithTag(OrderTrackingStatusTags.RETRY).assertDoesNotExist()
    compose.onNodeWithTag(TripMapTestTags.LOCATE).assertDoesNotExist()
  }

  @Test
  fun largeTextOnACompactCardKeepsRecenterReachableByScrolling() {
    var centers = 0
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        SampleAppTheme {
          OrderTrackingStatusCard(
              orderTrackingPresentation(OrderTrackingState.Position(fix), now),
              { centers++ },
              {},
              Modifier.width(280.dp).heightIn(max = 180.dp),
          )
        }
      }
    }
    compose
        .onNodeWithTag(TripMapTestTags.LOCATE)
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
    compose.runOnIdle { assertEquals(1, centers) }
  }
}
