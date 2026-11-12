package com.android.sample.ui.trips

import android.content.Context
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
import androidx.test.core.app.ApplicationProvider
import com.android.sample.R
import com.android.sample.data.repository.TripError
import com.android.sample.model.CriteriaProblem
import com.android.sample.model.HANDOFF_RADIUS_METERS
import com.android.sample.model.Location
import com.android.sample.model.StoreFilter
import com.android.sample.model.TripSearchCriteria
import com.android.sample.model.TripSort
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
      discoveryTrip(
          "migros-trip",
          Location("Migros", 46.52, 6.63),
          Location("EPFL", 46.52, 6.57),
          departure,
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
    compose
        .onNodeWithTag(AvailableTripsScreenTestTags.LOADING)
        .assertIsDisplayed()
        .assertContentDescriptionEquals(string(R.string.trips_loading))
  }

  @Test
  fun contentDisplaysStoreDepartureAndHandoff() {
    show(AvailableTripsUiState.Content(listOf(trip)))
    val expectedDeparture =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.US)
            .withZone(ZoneId.of("UTC"))
            .format(departure)
    compose
        .assertTripCard(trip)
        .assert(hasText(string(R.string.trips_departure, expectedDeparture)))
        .assert(hasText(string(R.string.trips_handoff, "EPFL")))
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
    val first = compose.assertTripCard(trip).fetchSemanticsNode()
    val second = compose.assertTripCard(earlier.id, "Coop").fetchSemanticsNode()
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
    compose.onTripCard(other.id).assert(hasText("Coop")).assertHasClickAction().performClick()
    compose.runOnIdle { assertEquals(listOf(other.id), selections) }
  }

  @Test
  fun emptyStateDisplaysExactMessage() {
    show(AvailableTripsUiState.Empty)
    assertStatus(AvailableTripsScreenTestTags.EMPTY, string(R.string.trips_empty))
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
    scrollTo(hasTestTag(AvailableTripsScreenTestTags.RETRY))
    compose
        .onNodeWithTag(AvailableTripsScreenTestTags.RETRY)
        .assertIsDisplayed()
        .assertHasClickAction()
        .performClick()
    compose.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun errorDisplaysSafeMessageAndRetryInvokesCallback() {
    var retries = 0
    show(AvailableTripsUiState.Error(TripError.Unknown), onRetry = { retries++ })
    assertStatus(AvailableTripsScreenTestTags.ERROR, string(R.string.trips_error))
    compose.runOnIdle { assertEquals(0, retries) }
    compose
        .onNodeWithTag(AvailableTripsScreenTestTags.RETRY)
        .assert(hasText(string(R.string.trips_retry)))
        .assertIsDisplayed()
        .assertHasClickAction()
        .performClick()
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
    val helper = string(R.string.trips_location_helper, HANDOFF_RADIUS_METERS.toInt())
    compose.onNodeWithText(helper).assertIsDisplayed().assertTextEquals(helper)
    compose
        .onNodeWithTag(AvailableTripsScreenTestTags.LOCATION_FIELD)
        .assert(hasText(string(R.string.trips_location_placeholder)))
        .assertHasClickAction()
        .performClick()
    compose
        .onNodeWithTag(CreateTripScreenTestTags.locationOption(rolex.name))
        .assert(hasText(rolex.name))
        .assertIsDisplayed()
    compose
        .onNodeWithTag(CreateTripScreenTestTags.locationOption(metro.name))
        .assert(hasText(metro.name))
        .assertIsDisplayed()
        .performClick()
    compose.runOnIdle { assertEquals(listOf(metro), picked) }
    compose.onNodeWithTag(AvailableTripsScreenTestTags.CLEAR_LOCATION).assertDoesNotExist()
  }

  @Test
  fun selectedPointShowsNameAndAccessibleClearAction() {
    var clears = 0
    showFilter(AvailableTripsUiState.Content(listOf(trip)), rolex, onClear = { clears++ })
    compose.onNodeWithTag(AvailableTripsScreenTestTags.LOCATION_FIELD).assert(hasText(rolex.name))
    compose.onNodeWithText(string(R.string.trips_location_placeholder)).assertDoesNotExist()
    compose.runOnIdle { assertEquals(0, clears) }
    compose
        .onNodeWithTag(AvailableTripsScreenTestTags.CLEAR_LOCATION)
        .assert(hasText(string(R.string.trips_location_clear)))
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
    assertStatus(
        AvailableTripsScreenTestTags.NO_MATCHES,
        string(R.string.trips_no_matches, rolex.name),
    )
    compose.onNodeWithTag(AvailableTripsScreenTestTags.EMPTY).assertDoesNotExist()
    state = AvailableTripsUiState.Empty
    assertStatus(AvailableTripsScreenTestTags.EMPTY, string(R.string.trips_empty))
    compose.onNodeWithTag(AvailableTripsScreenTestTags.NO_MATCHES).assertDoesNotExist()
    state = AvailableTripsUiState.Error(TripError.NetworkError)
    assertStatus(AvailableTripsScreenTestTags.ERROR, string(R.string.trips_error))
    scrollTo(hasTestTag(AvailableTripsScreenTestTags.RETRY))
    compose
        .onNodeWithTag(AvailableTripsScreenTestTags.RETRY)
        .assertIsDisplayed()
        .assert(hasText(string(R.string.trips_retry)))
        .performClick()
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
    compose
        .onNodeWithTag(AvailableTripsScreenTestTags.CLEAR_LOCATION)
        .assertIsDisplayed()
        .assert(hasText(string(R.string.trips_location_clear)))
        .performClick()
    compose.runOnIdle { assertEquals(1, clears) }
    scrollTo(hasTestTag(AvailableTripsScreenTestTags.NO_MATCHES))
    compose.onNodeWithTag(AvailableTripsScreenTestTags.NO_MATCHES).assertIsDisplayed()
  }

  @Test
  fun noMatchesWithoutPointShowsTheGenericFiltersMessage() {
    showFilter(AvailableTripsUiState.NoMatches(null), selected = rolex)
    assertStatus(AvailableTripsScreenTestTags.NO_MATCHES, string(R.string.trips_no_matches_filters))
    compose.onNodeWithText(string(R.string.trips_no_matches, rolex.name)).assertDoesNotExist()
    compose.onNodeWithTag(AvailableTripsScreenTestTags.RETRY).assertDoesNotExist()
  }

  @Test
  fun invalidCriteriaShowsTheProblemWithoutRetry() {
    var state by
        mutableStateOf<AvailableTripsUiState>(
            AvailableTripsUiState.InvalidCriteria(CriteriaProblem.INVALID_RANGE)
        )
    compose.setContent {
      MaterialTheme { AvailableTripsScreen(state = state, onRetry = {}, onTripSelected = {}) }
    }
    for ((problem, message) in
        listOf(
            CriteriaProblem.INVALID_RANGE to R.string.trips_range_invalid,
            CriteriaProblem.EXPIRED_RANGE to R.string.trips_range_expired,
        )) {
      compose.runOnIdle { state = AvailableTripsUiState.InvalidCriteria(problem) }
      assertStatus(AvailableTripsScreenTestTags.INVALID_CRITERIA, string(message))
      compose.onNodeWithTag(AvailableTripsScreenTestTags.RETRY).assertDoesNotExist()
      compose.onNodeWithTag(AvailableTripsScreenTestTags.NO_MATCHES).assertDoesNotExist()
      compose.onNodeWithTag(AvailableTripsScreenTestTags.ERROR).assertDoesNotExist()
    }
    compose.onNodeWithText(string(R.string.trips_range_invalid)).assertDoesNotExist()
  }

  @Test
  fun searchControlsAreAbsentWithoutCriteria() {
    show(AvailableTripsUiState.Content(listOf(trip)))
    compose.onNodeWithTag(TripSearchTestTags.TOGGLE).assertDoesNotExist()
    compose.assertTripCard(trip)
  }

  private fun showWithCriteria(selected: Location?, onChange: (TripSearchCriteria) -> Unit = {}) {
    compose.setContent {
      MaterialTheme {
        AvailableTripsScreen(
            state = AvailableTripsUiState.Content(listOf(trip)),
            onRetry = {},
            onTripSelected = {},
            selectedPoint = selected,
            points = listOf(rolex, metro),
            criteria = TripSearchCriteria(store = StoreFilter.MIGROS),
            onCriteriaChange = onChange,
        )
      }
    }
  }

  @Test
  fun searchControlsShowWithCriteriaAndDistanceNeedsASelectedPoint() {
    showWithCriteria(selected = null)
    compose
        .onNodeWithTag(TripSearchTestTags.TOGGLE)
        .assertIsDisplayed()
        .assert(hasText(string(R.string.trips_filters_active, string(R.string.trips_store_migros))))
        .performClick()
    compose.onNodeWithTag(TripSearchTestTags.sort(TripSort.DISTANCE)).assertIsNotEnabled()
  }

  @Test
  // Tall viewport: performScrollTo does not scroll the list to a chip inside the already-visible
  // controls item, so the sort row must be on screen to be clicked.
  @Config(qualifiers = "en-rUS-w412dp-h915dp")
  fun distanceSortIsEnabledWithASelectedPoint() {
    val emitted = mutableListOf<TripSearchCriteria>()
    showWithCriteria(selected = rolex) { emitted += it }
    compose.onNodeWithTag(TripSearchTestTags.TOGGLE).performClick()
    compose
        .onNodeWithTag(TripSearchTestTags.sort(TripSort.DISTANCE))
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    assertEquals(
        listOf(TripSearchCriteria(store = StoreFilter.MIGROS, sort = TripSort.DISTANCE)),
        emitted,
    )
  }

  private fun string(id: Int, vararg args: Any): String =
      ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

  /** Finds a discovery state by its tag only, then checks its wording from string resources. */
  private fun assertStatus(tag: String, message: String) {
    scrollTo(hasTestTag(tag))
    compose.onNodeWithTag(tag).assertIsDisplayed().assert(hasAnyDescendant(hasText(message)))
  }

  private fun scrollTo(matcher: SemanticsMatcher) {
    compose.onNode(hasScrollAction()).performScrollToNode(matcher)
  }
}
