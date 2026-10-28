package com.android.sample.ui.trips

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.sample.data.repository.TripError
import com.android.sample.model.HANDOFF_RADIUS_METERS
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class AvailableTripsScreenTest {
  @get:Rule val compose = createComposeRule()
  private lateinit var originalTimeZone: TimeZone
  private val departure = Instant.parse("2026-10-06T13:00:00Z")
  private val trip =
      Trip(
          id = "migros-trip",
          ownerId = "owner",
          store = Location("Migros", 46.52, 6.63),
          scheduledAt = departure,
          handoffLocation = Location("EPFL", 46.52, 6.57),
          status = TripStatus.PUBLISHED,
          createdAt = departure,
          updatedAt = departure,
      )

  @Before
  fun setUp() {
    originalTimeZone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
  }

  @After
  fun tearDown() {
    TimeZone.setDefault(originalTimeZone)
  }

  private fun show(
      state: AvailableTripsUiState,
      onRetry: () -> Unit = {},
      onTripSelected: (String) -> Unit = {},
  ) {
    compose.setContent {
      MaterialTheme {
        AvailableTripsScreen(state = state, onRetry = onRetry, onTripSelected = onTripSelected)
      }
    }
  }

  @Test
  fun loadingStateIsDisplayed() {
    show(AvailableTripsUiState.Loading)
    compose.onNodeWithContentDescription("Loading available trips").assertIsDisplayed()
  }

  @Test
  fun contentDisplaysStoreDepartureAndHandoff() {
    show(AvailableTripsUiState.Content(listOf(trip)))
    compose.onNodeWithText("Migros").assertIsDisplayed()
    val expectedDeparture =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.US)
            .withZone(ZoneId.of("UTC"))
            .format(departure)
    compose.onNodeWithText("Departure: $expectedDeparture").assertIsDisplayed()
    compose.onNodeWithText("EPFL \u00b7 Handoff point").assertIsDisplayed()
  }

  @Test
  fun multipleTripsRenderInSuppliedOrder() {
    val earlier =
        trip.copy(
            id = "coop-trip",
            store = Location("Coop", 46.52, 6.63),
            scheduledAt = departure.minusSeconds(3600),
        )
    show(AvailableTripsUiState.Content(listOf(trip, earlier)))
    val first = compose.onNodeWithText("Migros").assertIsDisplayed().fetchSemanticsNode()
    val second = compose.onNodeWithText("Coop").assertIsDisplayed().fetchSemanticsNode()
    assertTrue(
        "Trips must retain the supplied display order",
        first.boundsInRoot.bottom <= second.boundsInRoot.top,
    )
  }

  @Test
  fun clickingTripCardSelectsExactlyItsId() {
    val other = trip.copy(id = "other-trip", store = Location("Coop", 46.52, 6.63))
    val selections = mutableListOf<String>()
    show(
        AvailableTripsUiState.Content(listOf(trip, other)),
        onTripSelected = { selections.add(it) },
    )
    compose.runOnIdle { assertTrue(selections.isEmpty()) }
    compose.onNodeWithText("Coop").assertHasClickAction().performClick()
    compose.runOnIdle { assertEquals(listOf(other.id), selections) }
  }

  @Test
  fun emptyStateDisplaysExactMessage() {
    show(AvailableTripsUiState.Empty)
    compose
        .onNodeWithText("No trips available right now.")
        .assertIsDisplayed()
        .assertTextEquals("No trips available right now.")
  }

  @Test
  @Config(qualifiers = "en-rUS-w640dp-h320dp-land-mdpi")
  fun errorRetryIsReachableInShortViewportWithLargeText() {
    var retries = 0
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
        MaterialTheme {
          AvailableTripsScreen(
              state = AvailableTripsUiState.Error(TripError.Unknown),
              onRetry = { retries++ },
              onTripSelected = {},
          )
        }
      }
    }
    compose.onRoot().assertWidthIsEqualTo(640.dp).assertHeightIsEqualTo(320.dp)
    compose.runOnIdle { assertEquals(0, retries) }
    val retry = compose.onNodeWithText("Retry")
    scrollTo(hasText("Retry"))
    retry.assertIsDisplayed().assertHasClickAction().performClick()
    compose.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun errorDisplaysSafeMessageAndRetryInvokesCallback() {
    var retries = 0
    show(AvailableTripsUiState.Error(TripError.Unknown), onRetry = { retries++ })
    compose
        .onNodeWithText("Something went wrong while loading trips.")
        .assertIsDisplayed()
        .assertTextEquals("Something went wrong while loading trips.")
    compose.runOnIdle { assertEquals(0, retries) }
    compose.onNodeWithText("Retry").assertIsDisplayed().assertHasClickAction().performClick()
    compose.runOnIdle { assertEquals(1, retries) }
  }

  private val rolex = Location("Rolex Learning Center", 46.5183493, 6.5683414)
  private val metro = Location("EPFL metro station", 46.5221982, 6.566154)

  private fun showFilter(
      state: AvailableTripsUiState,
      selected: Location?,
      onSelect: (Location) -> Unit = {},
      onClear: () -> Unit = {},
      onRetry: () -> Unit = {},
  ) {
    compose.setContent {
      MaterialTheme {
        AvailableTripsScreen(
            state = state,
            onRetry = onRetry,
            onTripSelected = {},
            selectedPoint = selected,
            points = listOf(rolex, metro),
            onSelectPoint = onSelect,
            onClearPoint = onClear,
        )
      }
    }
  }

  @Test
  fun locationFieldShowsPlaceholderAndPickingReportsExactPoint() {
    val picked = mutableListOf<Location>()
    showFilter(AvailableTripsUiState.Content(listOf(trip)), null, onSelect = { picked.add(it) })
    val helper = "Trips within ${HANDOFF_RADIUS_METERS.toInt()} m of this point"
    compose.onNodeWithText(helper).assertIsDisplayed().assertTextEquals(helper)
    compose
        .onNodeWithTag(AvailableTripsScreenTestTags.LOCATION_FIELD)
        .assert(hasText("All locations"))
        .assertHasClickAction()
        .performClick()
    compose.onNodeWithText(rolex.name).assertIsDisplayed()
    compose.onNodeWithText(metro.name).assertIsDisplayed().performClick()
    compose.runOnIdle { assertEquals(listOf(metro), picked) }
    compose.onNodeWithTag(AvailableTripsScreenTestTags.CLEAR_LOCATION).assertDoesNotExist()
    compose.onNodeWithText("Clear location").assertDoesNotExist()
  }

  @Test
  fun selectedPointShowsNameAndAccessibleClearAction() {
    var clears = 0
    showFilter(AvailableTripsUiState.Content(listOf(trip)), rolex, onClear = { clears++ })
    compose.onNodeWithTag(AvailableTripsScreenTestTags.LOCATION_FIELD).assert(hasText(rolex.name))
    compose.onNodeWithText("All locations").assertDoesNotExist()
    compose.runOnIdle { assertEquals(0, clears) }
    compose
        .onNodeWithText("Clear location")
        .assertIsDisplayed()
        .assertHasClickAction()
        .performClick()
    compose.runOnIdle { assertEquals(1, clears) }
  }

  @Test
  fun noMatchesNamesTheLocationWhileEmptyAndErrorKeepTheirMessages() {
    var state by mutableStateOf<AvailableTripsUiState>(AvailableTripsUiState.NoMatches(rolex))
    var retries = 0
    compose.setContent {
      MaterialTheme {
        AvailableTripsScreen(
            state = state,
            onRetry = { retries++ },
            onTripSelected = {},
            selectedPoint = rolex,
            points = listOf(rolex, metro),
        )
      }
    }
    val noMatches =
        hasText("No trips near Rolex Learning Center yet.") and
            hasAnyAncestor(hasTestTag(AvailableTripsScreenTestTags.NO_MATCHES))
    scrollTo(noMatches)
    compose.onNode(noMatches).assertIsDisplayed()
    compose.onNodeWithText("No trips available right now.").assertDoesNotExist()
    state = AvailableTripsUiState.Empty
    scrollTo(hasText("No trips available right now."))
    compose.onNodeWithText("No trips available right now.").assertIsDisplayed()
    compose.onNodeWithTag(AvailableTripsScreenTestTags.NO_MATCHES).assertDoesNotExist()
    state = AvailableTripsUiState.Error(TripError.NetworkError)
    scrollTo(hasText("Something went wrong while loading trips."))
    compose.onNodeWithText("Something went wrong while loading trips.").assertIsDisplayed()
    scrollTo(hasText("Retry"))
    compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
    compose.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  @Config(qualifiers = "en-rUS-w640dp-h320dp-land-mdpi")
  fun filterAndStatusStayReachableInShortViewportWithLargeText() {
    var clears = 0
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
        MaterialTheme {
          AvailableTripsScreen(
              state = AvailableTripsUiState.NoMatches(rolex),
              onRetry = {},
              onTripSelected = {},
              selectedPoint = rolex,
              points = listOf(rolex, metro),
              onClearPoint = { clears++ },
          )
        }
      }
    }
    compose.onRoot().assertHeightIsEqualTo(320.dp)
    // The whole screen scrolls, so the picker, Clear and status card can each be reached.
    compose.onNodeWithTag(AvailableTripsScreenTestTags.LOCATION_FIELD).assertIsDisplayed()
    compose.onNodeWithText("Clear location").assertIsDisplayed().performClick()
    compose.runOnIdle { assertEquals(1, clears) }
    scrollTo(hasTestTag(AvailableTripsScreenTestTags.NO_MATCHES))
    compose.onNodeWithTag(AvailableTripsScreenTestTags.NO_MATCHES).assertIsDisplayed()
  }

  private fun scrollTo(matcher: SemanticsMatcher) {
    compose.onNode(hasScrollAction()).performScrollToNode(matcher)
  }
}
