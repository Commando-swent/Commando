package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.android.sample.R
import com.android.sample.data.location.DeviceLocationResult
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
  private var locationState by mutableStateOf<MapLocationUiState>(MapLocationUiState.Idle)
  private var locate: (() -> Unit)? = null

  private fun show(
      realBackground: Boolean = false,
      fontScale: Float = 1f,
      restoration: StateRestorationTester? = null,
  ) {
    val screen: @Composable () -> Unit = {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        SampleAppTheme {
          TripMapScreen(
              state,
              { refreshes++ },
              { id -> state = (state as TripMapUiState.Content).copy(selectedTripId = id) },
              { state = (state as TripMapUiState.Content).copy(selectedTripId = null) },
              { details = it },
              {},
              selectedPoint = point,
              locationState = locationState,
              onLocate = locate,
              onClearPoint = { clears++ },
              mapSurface = { if (realBackground) CampusMapBackground(it) else Box(it) },
          )
        }
      }
    }
    if (restoration != null) restoration.setContent(screen) else compose.setContent(screen)
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
  fun offscreenAndHeaderCoveredTripsRemainTouchableInLandscape() {
    val outside = trip.copy(handoffLocation = Location("Lausanne", 46.5167, 6.6291))
    val covered =
        trip.copy(
            id = "covered",
            handoffLocation = TripLocations.handoffPoints.first { it.name.contains("SwissTech") },
        )
    val trips = listOf(outside, covered)
    state = TripMapUiState.Content(trips)
    show()
    compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + outside.id).assertDoesNotExist()
    for (candidate in trips) {
      compose
          .onNodeWithTag(TripMapTestTags.OFF_MAP_PREFIX + candidate.id)
          .performScrollTo()
          .performTouchInput { click() }
      compose.onNodeWithTag(MockMapTestTags.DETAILS).performScrollTo().performTouchInput { click() }
      assertEquals(candidate.id, details)
      compose.runOnIdle { state = TripMapUiState.Content(trips) }
    }
  }

  @Test
  @Config(qualifiers = "en-rUS-w640dp-h320dp-land-mdpi")
  fun selectorCoveredTripsRemainTouchableWithTheCmFilter() {
    val trips =
        listOf("SwissTech", "metro", "Rolex").map { name ->
          trip.copy(
              id = name,
              handoffLocation = TripLocations.handoffPoints.first { it.name.contains(name) },
          )
        }
    state = TripMapUiState.Content(trips)
    show()
    for (candidate in trips) {
      compose
          .onNodeWithTag(TripMapTestTags.OFF_MAP_PREFIX + candidate.id)
          .performScrollTo()
          .performTouchInput { click() }
      compose.onNodeWithTag(MockMapTestTags.DETAILS).performScrollTo().performTouchInput { click() }
      assertEquals(candidate.id, details)
      compose.runOnIdle { state = TripMapUiState.Content(trips) }
    }
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
    val locate = compose.onNodeWithTag(TripMapTestTags.LOCATE).fetchSemanticsNode().boundsInRoot
    assertTrue(locate.bottom < card.top)
    assertEquals(map.bottom - 16f, card.bottom, 1f)
    assertTrue(card.top > header.bottom)
    compose.onNodeWithTag(NavigationTestTags.MAP_LIST).assertIsDisplayed()
  }

  @Test
  fun fallbackListsOnlyHiddenGroupsAmongTenTrips() {
    val hidden = trip.copy(id = "hidden", handoffLocation = Location("North", 46.5259, 6.566))
    val visible =
        (1..9).map {
          trip.copy(
              id = "visible-$it",
              handoffLocation = Location("Visible", 46.520, 6.561 + it * 0.001),
          )
        }
    state = TripMapUiState.Content(visible + hidden)
    show()
    compose.onNodeWithTag(TripMapTestTags.OFF_MAP_PREFIX + hidden.id).assertExists()
    visible.forEach {
      compose.onNodeWithTag(TripMapTestTags.OFF_MAP_PREFIX + it.id).assertDoesNotExist()
    }
  }

  @Test
  fun largeFontsKeepLocateAboveScrollableSingleAndSharedCards() {
    state = TripMapUiState.Content(listOf(trip, second), trip.id)
    show(fontScale = 2f)
    for (trips in listOf(listOf(trip, second), listOf(trip))) {
      compose.runOnIdle { state = TripMapUiState.Content(trips, trip.id) }
      val card = compose.onNodeWithTag(MockMapTestTags.SUMMARY).fetchSemanticsNode().boundsInRoot
      val locate = compose.onNodeWithTag(TripMapTestTags.LOCATE).fetchSemanticsNode().boundsInRoot
      val header = compose.onNodeWithTag(TripMapTestTags.HEADER).fetchSemanticsNode().boundsInRoot
      assertTrue("Locate must clear the actual card height", locate.bottom < card.top)
      assertTrue("Locate must clear the actual header", locate.top >= header.bottom)
      compose.onNodeWithTag(MockMapTestTags.DETAILS).performScrollTo().performTouchInput { click() }
      assertEquals(trip.id, details)
      compose.onNodeWithTag(TripMapTestTags.LOCATE).performTouchInput { click() }
      compose.waitForIdle()
      compose
          .onNodeWithText(
              ApplicationProvider.getApplicationContext<Context>()
                  .getString(R.string.trip_map_location_unavailable)
          )
          .assertIsDisplayed()
    }
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
    compose
        .onNodeWithTag(TripMapTestTags.MARKER_LABEL_PREFIX + trip.id, useUnmergedTree = true)
        .performTouchInput { click(Offset(width - 2f, center.y)) }
    compose.onNodeWithTag(MockMapTestTags.SUMMARY).assertIsDisplayed()
  }

  @Test
  fun locateShowsPositionAndDeviceDistanceWithoutChangingDeliveryFilter() {
    var requests = 0
    val fix = DeviceLocation(point.latitude, point.longitude, 150f, 1000)
    locate = { requests++ }
    show()
    compose.onNodeWithTag(TripMapTestTags.LOCATE).performClick()
    assertEquals(1, requests)
    compose.runOnIdle {
      locationState = MapLocationUiState.Resolved(DeviceLocationResult.Available(fix))
    }
    val surface = compose.onNodeWithTag(TripMapTestTags.SURFACE).fetchSemanticsNode().boundsInRoot
    val position =
        compose.onNodeWithTag(MapLocationTestTags.POSITION).fetchSemanticsNode().boundsInRoot
    assertEquals(surface.center.x, position.center.x, 1f)
    assertEquals(surface.center.y, position.center.y, 1f)
    compose.onNodeWithTag(TripMapTestTags.CLEAR_FILTER).assertIsDisplayed()
    compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + trip.id).performClick()
    val context = ApplicationProvider.getApplicationContext<Context>()
    compose
        .onNodeWithText(context.getString(R.string.map_location_nearby_distance, 0))
        .assertExists()
    compose.onNodeWithTag(MockMapTestTags.DETAILS).performScrollTo().performClick()
    assertEquals(trip.id, details)
    assertEquals(0, clears)
  }

  @Test
  fun everyLocationFallbackKeepsTripMarkersAndSelectionUsable() {
    show()
    val context = ApplicationProvider.getApplicationContext<Context>()
    for ((result, message) in
        listOf(
            DeviceLocationResult.PermissionDenied to R.string.map_location_denied,
            DeviceLocationResult.ServicesDisabled to R.string.map_location_disabled,
            DeviceLocationResult.Unavailable to R.string.trip_map_location_unavailable,
            DeviceLocationResult.Failure to R.string.map_location_failure,
        )) {
      compose.runOnIdle { locationState = MapLocationUiState.Resolved(result) }
      compose.onNodeWithText(context.getString(message)).assertIsDisplayed()
      compose.onNodeWithTag(MapLocationTestTags.POSITION).assertDoesNotExist()
      compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + trip.id).assertExists()
    }
  }

  @Test
  fun offCampusLocationIsExplainedWithoutDrawingAFalsePosition() {
    locationState =
        MapLocationUiState.Resolved(
            DeviceLocationResult.Available(DeviceLocation(47.0, 6.56, 150f, 1000))
        )
    show()
    compose.onNodeWithTag(MapLocationTestTags.POSITION).assertDoesNotExist()
    compose
        .onNodeWithText(
            ApplicationProvider.getApplicationContext<Context>()
                .getString(R.string.map_location_outside)
        )
        .assertIsDisplayed()
    compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + trip.id).performClick()
    compose.onNodeWithTag(MockMapTestTags.DETAILS).performScrollTo().performClick()
    compose.waitForIdle()
    assertEquals(trip.id, details)
  }

  @Test
  fun cameraPanMovesTheDotAndNewFixRecentersIt() {
    val fix = DeviceLocation(point.latitude, point.longitude, 150f, 1000)
    locationState = MapLocationUiState.Resolved(DeviceLocationResult.Available(fix))
    show()
    val surface = compose.onNodeWithTag(TripMapTestTags.SURFACE)
    val before =
        compose.onNodeWithTag(MapLocationTestTags.POSITION).fetchSemanticsNode().boundsInRoot.center
    surface.performTouchInput {
      swipe(Offset(center.x, center.y + 90), Offset(center.x + 60, center.y + 90))
    }
    val after =
        compose.onNodeWithTag(MapLocationTestTags.POSITION).fetchSemanticsNode().boundsInRoot.center
    assertTrue("Dot before=$before after=$after", after.x > before.x + 10)
    compose.runOnIdle {
      locationState =
          MapLocationUiState.Resolved(
              DeviceLocationResult.Available(fix.copy(elapsedRealtimeMillis = 1001))
          )
    }
    val recentered =
        compose.onNodeWithTag(MapLocationTestTags.POSITION).fetchSemanticsNode().boundsInRoot.center
    assertEquals(before.x, recentered.x, 1f)
    assertEquals(before.y, recentered.y, 1f)
  }

  @Test
  fun recenteringKeepsTripsOutsideTheNewViewportSelectable() {
    val far = trip.copy(id = "edge", handoffLocation = Location("Campus edge", 46.525, 6.572))
    state = TripMapUiState.Content(listOf(far))
    locationState =
        MapLocationUiState.Resolved(
            DeviceLocationResult.Available(
                DeviceLocation(point.latitude, point.longitude, 150f, 1000)
            )
        )
    show()
    compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + far.id).assertDoesNotExist()
    compose.onNodeWithTag(TripMapTestTags.OFF_MAP_PREFIX + far.id).performScrollTo().performClick()
    compose.onNodeWithTag(MockMapTestTags.DETAILS).performScrollTo().performClick()
    compose.waitForIdle()
    assertEquals(far.id, details)
  }

  @Test
  @Config(qualifiers = "en-rUS-w640dp-h320dp-land-mdpi")
  fun locationEnabledKeepsTripDetailsReachableWithLargeFontsInLandscape() {
    state = TripMapUiState.Content(listOf(trip), trip.id)
    locationState =
        MapLocationUiState.Resolved(
            DeviceLocationResult.Available(
                DeviceLocation(point.latitude, point.longitude, 150f, 1000)
            )
        )
    show(fontScale = 2f)
    compose.onNodeWithTag(MockMapTestTags.DETAILS).performScrollTo().performClick()
    compose.waitForIdle()
    assertEquals(trip.id, details)
  }

  @Test
  fun imprecisePositionDoesNotAdvertiseNearbyTrips() {
    val fix = DeviceLocation(point.latitude, point.longitude, 2000f, 1000)
    state = TripMapUiState.Content(listOf(trip), trip.id)
    locationState = MapLocationUiState.Resolved(DeviceLocationResult.Available(fix))
    show()
    val context = ApplicationProvider.getApplicationContext<Context>()
    compose.onNodeWithText(context.getString(R.string.map_location_imprecise)).assertIsDisplayed()
    compose
        .onNodeWithText(context.getString(R.string.map_location_nearby_distance, 0))
        .assertDoesNotExist()
    compose
        .onNodeWithText(context.resources.getQuantityString(R.plurals.trip_map_count, 1, 1))
        .assertIsDisplayed()
  }

  @Test
  fun loadingKeepsTheUserCamera() {
    assertCameraSurvives(MapLocationUiState.Loading)
  }

  @Test
  fun expiredFixKeepsTheUserCamera() {
    assertCameraSurvives(MapLocationUiState.Idle)
  }

  private fun assertCameraSurvives(next: MapLocationUiState) {
    locationState =
        MapLocationUiState.Resolved(
            DeviceLocationResult.Available(
                DeviceLocation(point.latitude, point.longitude, 150f, 1000)
            )
        )
    show()
    val marker = compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + trip.id)
    compose.onNodeWithTag(TripMapTestTags.SURFACE).performTouchInput {
      swipe(Offset(center.x, center.y + 90), Offset(center.x + 60, center.y + 90))
    }
    val before = marker.fetchSemanticsNode().boundsInRoot.center
    compose.runOnIdle { locationState = next }
    val after = marker.fetchSemanticsNode().boundsInRoot.center
    assertEquals(before.x, after.x, 1f)
    assertEquals(before.y, after.y, 1f)
  }

  @Test
  fun restoredCameraKeepsUserPanInsteadOfRecenteringTheSameFix() {
    val restoration = StateRestorationTester(compose)
    locationState =
        MapLocationUiState.Resolved(
            DeviceLocationResult.Available(
                DeviceLocation(point.latitude, point.longitude, 150f, 1000)
            )
        )
    show(restoration = restoration)
    val dot = compose.onNodeWithTag(MapLocationTestTags.POSITION)
    val initial = dot.fetchSemanticsNode().boundsInRoot.center
    compose.onNodeWithTag(TripMapTestTags.SURFACE).performTouchInput {
      swipe(Offset(center.x, center.y + 90), Offset(center.x + 60, center.y + 90))
    }
    val before = dot.fetchSemanticsNode().boundsInRoot.center
    assertTrue(before.x > initial.x + 10)
    restoration.emulateSavedInstanceStateRestore()
    val after = dot.fetchSemanticsNode().boundsInRoot.center
    assertEquals(before.x, after.x, 1f)
    assertEquals(before.y, after.y, 1f)
  }
}
