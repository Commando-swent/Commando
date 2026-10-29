package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.android.sample.R
import com.android.sample.data.repository.TripError
import com.android.sample.model.*
import com.android.sample.ui.navigation.NavigationTestTags
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TripMapScreenTest {
  @get:Rule val compose = createComposeRule()
  private val point = TripLocations.handoffPoints.first { it.name == "CM building" }
  private val now = Instant.parse("2026-10-28T12:00:00Z")
  private val trip =
      Trip(
          "real / % 雪",
          "owner",
          TripLocations.stores.first(),
          now.plusSeconds(3600),
          point,
          TripStatus.PUBLISHED,
          now,
          now,
      )
  private val second = trip.copy(id = "second", store = TripLocations.stores.last())
  private var state by mutableStateOf<TripMapUiState>(TripMapUiState.Content(listOf(trip, second)))
  private var details: String? = null
  private var refreshes = 0
  private var clears = 0

  private fun show(realBackground: Boolean = false) {
    compose.setContent {
      SampleAppTheme {
        TripMapScreen(
            state,
            { refreshes++ },
            { id -> state = (state as TripMapUiState.Content).copy(selectedTripId = id) },
            { state = (state as TripMapUiState.Content).copy(selectedTripId = null) },
            { details = it },
            {},
            selectedPoint = point,
            onClearPoint = { clears++ },
            mapSurface = { if (realBackground) CampusMapBackground(it) else Box(it) },
        )
      }
    }
  }

  @Test
  fun sharedPointSelectionOpensTheActualTripIdWithoutDemoActions() {
    show()
    compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + trip.id).performClick()
    compose.onNodeWithTag(MockMapTestTags.PAGE_PREFIX + second.id).performScrollTo().performClick()
    compose.onNodeWithTag(MockMapTestTags.DETAILS).performScrollTo().performClick()
    assertEquals(second.id, details)
    compose.onNodeWithTag(MockMapTestTags.LOCATION).assertDoesNotExist()
    compose.onNodeWithTag(MockMapTestTags.ALLOW).assertDoesNotExist()
  }

  @Test
  fun refreshAndClearDeliveryLocationEmitTheirCallbacks() {
    show()
    compose.onNodeWithTag(TripMapTestTags.REFRESH).performClick()
    compose.onNodeWithTag(TripMapTestTags.CLEAR_FILTER).performClick()
    assertEquals(1, refreshes)
    assertEquals(1, clears)
  }

  @Test
  fun loadingEmptyNoMatchesErrorAndSessionEndHaveDistinctFeedback() {
    show()
    val context = ApplicationProvider.getApplicationContext<Context>()
    for ((next, message) in
        listOf(
            TripMapUiState.Loading to context.getString(R.string.trip_map_loading),
            TripMapUiState.Empty to context.getString(R.string.mock_map_empty_title),
            TripMapUiState.NoMatches(point) to
                context.getString(R.string.trips_no_matches, point.name),
            TripMapUiState.Error(TripError.NetworkError) to
                context.getString(R.string.mock_map_error_title),
            TripMapUiState.SessionEnded to context.getString(R.string.trip_map_session_ended),
        )) {
      compose.runOnIdle { state = next }
      compose.onNodeWithText(message).assertIsDisplayed()
      compose.onNodeWithTag(MockMapTestTags.SUMMARY).assertDoesNotExist()
      if (next is TripMapUiState.Error) compose.onNodeWithTag(MockMapTestTags.RETRY).performClick()
    }
    assertEquals(1, refreshes)
  }

  @Test
  @Config(qualifiers = "en-rUS-w640dp-h320dp-land-mdpi")
  fun offscreenTripRemainsSelectableInsteadOfBeingClampedToTheMap() {
    val outside = trip.copy(handoffLocation = Location("Lausanne", 46.5167, 6.6291))
    state = TripMapUiState.Content(listOf(outside))
    show()
    compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + trip.id).assertDoesNotExist()
    compose.onNodeWithTag(TripMapTestTags.OFF_MAP_PREFIX + trip.id).performScrollTo().performClick()
    compose.onNodeWithTag(MockMapTestTags.DETAILS).performScrollTo().performClick()
    assertEquals(trip.id, details)
  }

  @Test
  fun selectedCardOverlaysTheFullViewportBelowTheFigmaHeader() {
    state = TripMapUiState.Content(listOf(trip, second), trip.id)
    show()
    val map = compose.onNodeWithTag(TripMapTestTags.SURFACE).fetchSemanticsNode().boundsInRoot
    val header = compose.onNodeWithTag(TripMapTestTags.HEADER).fetchSemanticsNode().boundsInRoot
    val card = compose.onNodeWithTag(MockMapTestTags.SUMMARY).fetchSemanticsNode().boundsInRoot
    assertTrue(map.height > map.width * 1.5f)
    assertEquals(78f, header.height, 1f)
    assertEquals(map.width - 32f, card.width, 1f)
    assertEquals(239f, card.height, 1f)
    assertEquals(map.bottom - 16f, card.bottom, 1f)
    assertTrue(card.top > header.bottom)
    compose.onNodeWithTag(NavigationTestTags.MAP_LIST).assertIsDisplayed()
  }

  @Test
  fun westernLabelStaysVisibleWithoutMovingItsGeographicPin() {
    val edge = point.copy(longitude = 6.5591)
    state = TripMapUiState.Content(listOf(trip.copy(handoffLocation = edge)))
    show(realBackground = true)
    val map = compose.onNodeWithTag(TripMapTestTags.SURFACE).getUnclippedBoundsInRoot()
    val marker =
        compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + trip.id).getUnclippedBoundsInRoot()
    val label =
        compose
            .onNodeWithTag(TripMapTestTags.MARKER_LABEL_PREFIX + trip.id, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
    val position = CAMPUS_MAP_PROJECTION.project(edge)!!
    assertEquals(
        map.left.value + (map.right - map.left).value * position.x.toFloat(),
        marker.left.value + (marker.right - marker.left).value / 2,
        1f,
    )
    assertEquals(
        map.top.value + (map.bottom - map.top).value * position.y.toFloat(),
        marker.bottom.value,
        1f,
    )
    assertTrue(label.left >= map.left)
    assertTrue(label.right <= map.right)
  }
}
