package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sample.R
import com.android.sample.model.*
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Person summaries retain actual trip and order IDs when positions are unavailable. */
@RunWith(RobolectricTestRunner::class)
class LiveMapPanelTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun selectingAnUnavailableCommandoOpensEachActualOrder() {
    val time = Instant.now()
    val trip =
        Trip(
            "trip",
            "alice",
            TripLocations.stores.first(),
            time.plusSeconds(3600),
            TripLocations.handoffPoints.first(),
            TripStatus.IN_PROGRESS,
            time,
            time,
        )
    val order =
        Order(
            "order / % 雪",
            trip.id,
            "viewer",
            listOf(OrderItem("milk", "Milk", 200, 1)),
            0,
            OrderStatus.ACCEPTED,
            time,
        )
    val second = order.copy(id = "second")
    val state =
        LiveMapState.Content(
            LiveMapMode.TRACKING,
            listOf(LiveCommando("alice", listOf(trip), listOf(order, second))),
        )
    var selected: String? by mutableStateOf(null)
    var opened: String? = null
    compose.setContent {
      SampleAppTheme { LiveMapPanel(state, selected, { selected = it }, {}, { opened = it }, {}) }
    }
    compose.onNodeWithTag(LiveMapTestTags.PERSON_PREFIX + "alice").performScrollTo().performClick()
    compose.onNodeWithTag(LiveMapTestTags.ORDER_PREFIX + second.id).performScrollTo().performClick()
    compose.runOnIdle { assertEquals(second.id, opened) }
    compose.onNodeWithTag(LiveMapTestTags.ORDER_PREFIX + order.id).performScrollTo().performClick()
    compose.runOnIdle { assertEquals(order.id, opened) }
  }

  @Test
  fun discoveryExpandsAnOffMapPersonAndOpensTheActualTrip() {
    val now = Instant.now()
    val trip =
        Trip(
            "opaque trip / % 雪",
            "alice",
            TripLocations.stores.first(),
            now.plusSeconds(3600),
            TripLocations.handoffPoints.first(),
            TripStatus.PUBLISHED,
            now,
            now,
        )
    val person =
        LiveCommando("alice", listOf(trip), position = CommandoPosition(46.54, 6.566, 100.0, now))
    var state: LiveMapState by mutableStateOf(LiveMapState.Loading)
    var selected: String? by mutableStateOf(null)
    var retries = 0
    var opened: String? = null
    compose.setContent {
      SampleAppTheme {
        LiveMapPanel(state, selected, { selected = it }, { opened = it }, {}, { retries++ })
      }
    }
    compose.onNodeWithTag(LiveMapTestTags.LOADING).assertExists()
    compose.runOnIdle { state = LiveMapState.Unavailable }
    compose.onNodeWithTag(LiveMapTestTags.RETRY).performClick()
    compose.runOnIdle {
      assertEquals(1, retries)
      state = LiveMapState.Content(LiveMapMode.DISCOVERY, listOf(person))
    }
    val countLabel =
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .resources
            .getQuantityString(R.plurals.live_discovery_count, 1, 1)
    compose.onNodeWithTag(LiveMapTestTags.PERSON_PREFIX + "alice").assertDoesNotExist()
    compose.onNodeWithText(countLabel).performClick()
    compose.onNodeWithTag(LiveMapTestTags.PERSON_PREFIX + "alice").performScrollTo().performClick()
    compose.onNodeWithTag(LiveMapTestTags.TRIP_PREFIX + trip.id).performScrollTo().performClick()
    compose.runOnIdle { assertEquals(trip.id, opened) }
    compose.onNodeWithText(countLabel).performScrollTo().performClick()
    compose.onNodeWithTag(LiveMapTestTags.PERSON_PREFIX + "alice").assertDoesNotExist()
  }
}
