package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.android.sample.R
import com.android.sample.model.*
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Controls expose actual opt-in and real IDs, including several orders from one unavailable person.
 */
@RunWith(RobolectricTestRunner::class)
class LiveMapControlsTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun failedDeletionStaysCheckedAndAllowsTheUserToRetryStopping() {
    var state: PositionSharingState by mutableStateOf(PositionSharingState.Off)
    var requested: Boolean? = null
    compose.setContent { SampleAppTheme { PositionSharingCard(state, { requested = it }) } }
    compose.onNodeWithTag(LiveMapTestTags.SHARING_TOGGLE).assertIsOff().performClick()
    compose.runOnIdle {
      assertEquals(true, requested)
      state = PositionSharingState.Live
    }
    compose.onNodeWithTag(LiveMapTestTags.SHARING_TOGGLE).assertIsOn()
    compose.runOnIdle { state = PositionSharingState.Failed(SharingFailureReason.STOP) }
    compose.onNodeWithTag(LiveMapTestTags.SHARING_TOGGLE).assertIsOn().performClick()
    compose.runOnIdle { assertEquals(false, requested) }
  }

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
  fun sharingFailuresRemainVisibleAndStoppingPreventsRepeatedTaps() {
    var state: PositionSharingState by mutableStateOf(PositionSharingState.Starting)
    var text = ""
    compose.setContent {
      text = stringResource(R.string.position_sharing_starting)
      SampleAppTheme { PositionSharingCard(state, {}) }
    }
    compose.onNodeWithText(text).assertExists()
    compose.onNodeWithTag(LiveMapTestTags.SHARING_TOGGLE).assertIsOn()
    compose.runOnIdle { state = PositionSharingState.Stopping }
    compose.onNodeWithTag(LiveMapTestTags.SHARING_TOGGLE).assertIsNotEnabled()
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
      compose.onNodeWithTag(LiveMapTestTags.SHARING_TOGGLE).assertIsOff().assertIsEnabled()
    }
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
      selected = "alice"
    }
    compose.onNodeWithTag(LiveMapTestTags.TRIP_PREFIX + trip.id).performScrollTo().performClick()
    compose.runOnIdle { assertEquals(trip.id, opened) }
  }

  @Test
  fun stopRemainsReachableWhenTheHomeTripDisappearsOrFailsToLoad() {
    var home: com.android.sample.ui.home.HomeTripUiState by
        mutableStateOf(com.android.sample.ui.home.HomeTripUiState.Empty)
    var stopped = false
    compose.setContent {
      SampleAppTheme {
        com.android.sample.ui.home.HomeScreen(
            com.android.sample.ui.navigation.AppMode.Commando,
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
        .onNodeWithTag(LiveMapTestTags.SHARING_TOGGLE)
        .performScrollTo()
        .assertIsOn()
        .performClick()
    compose.runOnIdle {
      assertTrue(stopped)
      home = com.android.sample.ui.home.HomeTripUiState.Loading
    }
    compose.onNodeWithTag(LiveMapTestTags.SHARING_TOGGLE).performScrollTo().assertIsOn()
  }
}
