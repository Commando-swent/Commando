package com.android.sample.ui.trips

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.sample.data.repository.TripError
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
    retry.assertIsNotDisplayed()
    retry.performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
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
}
