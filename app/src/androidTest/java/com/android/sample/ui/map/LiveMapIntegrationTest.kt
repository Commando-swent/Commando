package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.android.sample.model.*
import com.android.sample.ui.theme.SampleAppTheme
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Live map selection retains actual order IDs; positions are deterministic injected fixtures. */
@RunWith(AndroidJUnit4::class)
class LiveMapIntegrationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun liveMarkersAndUnavailableCommandosOpenActualAssociatedOrders() {
    val now = Instant.now()
    val a = trip("a", "alice", now)
    val b = trip("b", "bob", now)
    val order =
        Order(
            "actual order / % 雪",
            b.id,
            "viewer",
            listOf(OrderItem("milk", "Milk", 220, 1)),
            0,
            OrderStatus.ACCEPTED,
            now,
        )
    val live =
        LiveMapState.Content(
            LiveMapMode.TRACKING,
            listOf(
                LiveCommando(
                    "alice",
                    listOf(a),
                    listOf(order.copy(id = "alice-order", tripId = a.id)),
                    CommandoPosition(46.521, 6.566, 100.0, now),
                ),
                LiveCommando("bob", listOf(b), listOf(order)),
            ),
        )
    var opened: String? = null
    compose.setContent {
      SampleAppTheme {
        TripMapScreen(
            TripMapUiState.Content(emptyList()),
            {},
            {},
            {},
            {},
            {},
            liveState = live,
            onLiveOrder = { opened = it },
        )
      }
    }
    compose.onNodeWithTag(LiveMapTestTags.TRACKING).assertExists()
    compose.onNodeWithTag(LiveMapTestTags.MARKER_PREFIX + "alice").assertExists()
    compose.onNodeWithTag(LiveMapTestTags.MARKER_PREFIX + "bob").assertDoesNotExist()
    compose.onNodeWithTag(LiveMapTestTags.PERSON_PREFIX + "bob").performScrollTo().performClick()
    screenshot("live-map-tracking.png")
    compose.onNodeWithTag(LiveMapTestTags.ORDER_PREFIX + order.id).performScrollTo().performClick()
    compose.runOnIdle { assertEquals(order.id, opened) }
  }

  private fun screenshot(name: String) {
    compose.waitForIdle()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val output = File(context.getExternalFilesDir(null), name)
    assertTrue(
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(output)
    )
  }

  private fun trip(id: String, owner: String, now: Instant) =
      Trip(
          id,
          owner,
          TripLocations.stores.first(),
          now.plusSeconds(3600),
          TripLocations.handoffPoints.first(),
          TripStatus.PUBLISHED,
          now,
          now,
      )
}
