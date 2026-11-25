package com.commando.app.ui.map

// AI assistance: OpenAI Codex.
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sample.R
import com.commando.app.model.*
import com.commando.app.ui.theme.SampleAppTheme
import com.commando.app.ui.trips.discoveryTrip
import java.io.File
import java.time.Instant
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real campus projection and grouped, exact-ID actions without backend or location permission. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp-mdpi")
class LiveMapScreenTest {
  @get:Rule val compose = createComposeRule()
  private val now = Instant.now()
  private val trip =
      discoveryTrip(
          "trip % 雪",
          TripLocations.stores.first(),
          CAMPUS_DISCOVERY_CENTER,
          now.plusSeconds(3600),
          "alice",
          now,
      )
  private val fix = CommandoPosition(46.523, 6.566, 20.0, now)

  private fun order(id: String) =
      Order(
          id,
          trip.id,
          "viewer",
          listOf(OrderItem("milk", "Milk", 200, 1)),
          0,
          OrderStatus.PENDING,
          now,
      )

  private var state: LiveMapState by mutableStateOf(LiveMapState.Loading)
  private var selected: String? = null
  private var retries = 0

  private fun show() {
    compose.setContent {
      SampleAppTheme {
        TripMapScreen(
            TripMapUiState.Content(emptyList()),
            { retries++ },
            {},
            {},
            { selected = it },
            {},
            liveState = state,
            onOrderDetails = { selected = it },
            mapSurface = { Box(it) },
        )
      }
    }
  }

  private fun capture(name: String) {
    val image = compose.onRoot().captureToImage().asAndroidBitmap()
    val file = File("build/reports/live-map/$name.png")
    file.parentFile.mkdirs()
    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  @Test
  fun unconfirmedErrorsEmptyAndInvalidFiltersRemainDistinctAndRetryable() {
    show()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    compose.onNodeWithText(context.getString(R.string.live_map_loading)).assertExists()
    compose.onAllNodesWithText(context.getString(R.string.live_map_discovery)).assertCountEquals(0)
    capture("loading")
    compose.runOnIdle { state = LiveMapState.Unavailable }
    capture("error")
    compose.onNodeWithTag(LiveMapTags.RETRY).performClick()
    compose.runOnIdle {
      assertEquals(1, retries)
      state = LiveMapState.Content(LiveMapMode.DISCOVERY, emptyList())
    }
    compose.onNodeWithText(context.getString(R.string.live_map_empty)).assertExists()
    capture("empty")
    compose.runOnIdle {
      state =
          LiveMapState.Content(LiveMapMode.DISCOVERY, emptyList(), CriteriaProblem.INVALID_RANGE)
    }
    compose.onNodeWithText(context.getString(R.string.live_map_filters)).assertExists()
    compose.onNodeWithText(context.getString(R.string.live_map_empty)).assertDoesNotExist()
  }

  @Test
  fun markersSelectACommandoWhileEveryOrderSurvivesMissingAndOffMapFixes() {
    val first = LiveCommando("alice", listOf(trip), listOf(order("one % 雪"), order("two?#")), fix)
    val second =
        LiveCommando(
            "bob",
            listOf(trip.copy(ownerId = "bob")),
            listOf(order("three")),
            fix.copy(latitude = 0.0),
        )
    state = LiveMapState.Content(LiveMapMode.TRACKING, listOf(first, second))
    show()
    capture("tracking")
    compose.onNodeWithTag(LiveMapTags.MARKER + "alice").performClick()
    compose.onNodeWithTag(LiveMapTags.MARKER + "bob").assertDoesNotExist()
    compose.onNodeWithTag(LiveMapTags.ORDER + "two?#").performScrollTo().performClick()
    compose.runOnIdle { assertEquals("two?#", selected) }
    compose.onNodeWithTag(LiveMapTags.ALL).performScrollTo().performClick()
    compose.onNodeWithTag(LiveMapTags.ORDER + "three").performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals("three", selected)
      state = LiveMapState.Content(LiveMapMode.TRACKING, listOf(first.copy(position = null)))
    }
    capture("missing-position")
    compose.onNodeWithTag(LiveMapTags.MARKER + "alice").assertDoesNotExist()
    compose.onNodeWithTag(LiveMapTags.ORDER + "one % 雪").performScrollTo().performClick()
    compose.runOnIdle { assertEquals("one % 雪", selected) }
  }

  @Test
  @Config(qualifiers = "en-rUS-w917dp-h412dp-mdpi")
  fun discoveryTripLinksAndSummariesRemainReachableInLandscapeAndAfterModeChanges() {
    state =
        LiveMapState.Content(
            LiveMapMode.DISCOVERY,
            listOf(LiveCommando("alice", listOf(trip), position = fix)),
        )
    show()
    capture("discovery-landscape")
    compose.onNodeWithTag(LiveMapTags.TRIP + trip.id).performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(trip.id, selected)
      state = LiveMapState.Loading
    }
    compose.onNodeWithTag(LiveMapTags.TRIP + trip.id).assertDoesNotExist()
    compose.onNodeWithTag(LiveMapTags.MARKER + "alice").assertDoesNotExist()
  }
}
