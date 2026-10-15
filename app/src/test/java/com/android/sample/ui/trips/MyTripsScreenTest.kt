package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.TripError
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class MyTripsScreenTest {
  @get:Rule val compose = createComposeRule()
  private val now = Instant.parse("2026-10-08T12:00:00Z")
  private val migros = trip("migros", "Migros EPFL", TripStatus.PUBLISHED, "EPFL metro station")
  private val denner = trip("denner", "Denner EPFL", TripStatus.IN_PROGRESS, "SV building")

  private val selected = mutableListOf<String>()
  private var publishClicks = 0
  private var retryClicks = 0

  private fun trip(id: String, store: String, status: TripStatus, handoff: String) =
      Trip(
          id = id,
          ownerId = "owner",
          store = Location(store, 46.52, 6.63),
          scheduledAt = now.plusSeconds(3600),
          handoffLocation = Location(handoff, 46.52, 6.57),
          status = status,
          createdAt = now,
          updatedAt = now,
      )

  private fun show(state: MyTripsUiState) {
    compose.setContent {
      SampleAppTheme {
        MyTripsScreen(
            state = state,
            onRetry = { retryClicks++ },
            onTripSelected = { selected += it },
            onPublishTrip = { publishClicks++ },
        )
      }
    }
  }

  @Test
  fun headerIsAlwaysShown() {
    show(MyTripsUiState.Loading)
    compose.onNodeWithText("My Trips").assertIsDisplayed()
    compose.onNodeWithText("Trips you have published.").assertIsDisplayed()
  }

  @Test
  fun loadingShowsSkeletonOnly() {
    show(MyTripsUiState.Loading)
    compose
        .onNodeWithTag(MyTripsTestTags.LOADING)
        .assertIsDisplayed()
        .assertContentDescriptionEquals("Loading your trips")
    compose.onNodeWithTag(MyTripsTestTags.PUBLISH_TRIP).assertDoesNotExist()
    compose.onNodeWithTag(MyTripsTestTags.RETRY).assertDoesNotExist()
  }

  @Test
  fun contentShowsEachTripWithDepartureAndHandoff() {
    show(MyTripsUiState.Content(listOf(migros, denner)))
    compose.onNodeWithText("Migros EPFL").assertIsDisplayed()
    compose.onNodeWithText("Denner EPFL").assertIsDisplayed()
    compose.onNodeWithText("Handoff: EPFL metro station").assertIsDisplayed()
    val departure = formatTripDeparture(migros.scheduledAt, Locale.US, ZoneId.systemDefault())
    compose.onAllNodesWithText(departure).onFirst().assertIsDisplayed()
    compose.onNodeWithTag(MyTripsTestTags.EMPTY).assertDoesNotExist()
    compose.onNodeWithTag(MyTripsTestTags.ERROR).assertDoesNotExist()
  }

  /** One card per test: a lazy list only composes the cards that fit on the test screen. */
  private fun assertStatusLabel(status: TripStatus, label: String) {
    val trip = migros.copy(status = status)
    show(MyTripsUiState.Content(listOf(trip)))
    // Cards merge their children, so the badge is only found in the unmerged tree.
    compose
        .onNodeWithTag(MyTripsTestTags.STATUS_PREFIX + trip.id, useUnmergedTree = true)
        .assertTextEquals(label)
  }

  @Test fun publishedStatusIsShown() = assertStatusLabel(TripStatus.PUBLISHED, "Published")

  @Test fun inProgressStatusIsShown() = assertStatusLabel(TripStatus.IN_PROGRESS, "In progress")

  @Test fun completedStatusIsShown() = assertStatusLabel(TripStatus.COMPLETED, "Completed")

  @Test fun cancelledStatusIsShown() = assertStatusLabel(TripStatus.CANCELLED, "Cancelled")

  @Test
  fun selectingATripImmediatelyReportsItsId() {
    show(MyTripsUiState.Content(listOf(migros, denner)))
    compose.onNodeWithTag(MyTripsTestTags.TRIP_CARD_PREFIX + denner.id).performClick()
    compose.runOnIdle { assertEquals(listOf(denner.id), selected) }
  }

  @Test
  fun publishButtonOnContentImmediatelyRequestsCreateTrip() {
    show(MyTripsUiState.Content(listOf(migros)))
    compose.onNodeWithText("Publish a trip", useUnmergedTree = true).assertIsDisplayed()
    compose.onNodeWithTag(MyTripsTestTags.PUBLISH_TRIP).assertIsDisplayed().performClick()
    compose.runOnIdle { assertEquals(1, publishClicks) }
  }

  @Test
  fun emptyStateOffersPublishATrip() {
    show(MyTripsUiState.Empty)
    compose.onNodeWithTag(MyTripsTestTags.EMPTY).assertIsDisplayed()
    compose.onNodeWithText("No trips published yet.").assertIsDisplayed()
    compose.onNodeWithTag(MyTripsTestTags.PUBLISH_TRIP).performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(1, publishClicks)
      assertEquals(0, retryClicks)
    }
  }

  @Test
  fun errorStateRetriesWithoutPublishing() {
    show(MyTripsUiState.Error(TripError.NetworkError))
    compose.onNodeWithTag(MyTripsTestTags.ERROR).assertIsDisplayed()
    compose.onNodeWithText("Something went wrong while loading your trips.").assertIsDisplayed()
    compose.onNodeWithTag(MyTripsTestTags.PUBLISH_TRIP).assertDoesNotExist()
    compose.onNodeWithTag(MyTripsTestTags.RETRY).performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(1, retryClicks)
      assertEquals(0, publishClicks)
    }
  }

  @Test
  fun viewModelOverloadDisplaysOnlyTheOwnersTrips() {
    val others =
        migros.copy(id = "others", ownerId = "someone-else", store = Location("Other", 0.0, 0.0))
    val viewModel =
        MyTripsViewModel(
            FakeTripRepository("owner", now = { now }, initialTrips = listOf(migros, others)),
            "owner",
        )
    compose.setContent {
      SampleAppTheme {
        MyTripsScreen(viewModel, onTripSelected = { selected += it }, onPublishTrip = {})
      }
    }
    compose.onNodeWithText("Migros EPFL").assertIsDisplayed()
    compose.onNodeWithText("Other").assertDoesNotExist()
  }
}
