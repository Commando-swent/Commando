package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Trip

/** Shared lookups and repository wrapper for the trip discovery tests; see [discoveryTrip]. */

/** Delegates to [base], counting list loads and recording every requested trip ID. */
class ScriptedTripRepository(
    private val base: TripRepository,
    private val upcoming: suspend () -> TripResult<List<Trip>> = { base.getUpcomingTrips() },
    private val details: suspend (String) -> TripResult<Trip> = { base.getTripById(it) },
) : TripRepository by base {
  var upcomingLoads = 0
    private set

  private val requested = mutableListOf<String>()

  /** Live, read-only view of every ID passed to [getTripById], in call order. */
  val requestedIds: List<String> = requested

  override fun observeUpcomingTrips() = kotlinx.coroutines.flow.flow { emit(getUpcomingTrips()) }

  override suspend fun getUpcomingTrips(): TripResult<List<Trip>> {
    upcomingLoads++
    return upcoming()
  }

  override suspend fun getTripById(tripId: String): TripResult<Trip> {
    requested.add(tripId)
    return details(tripId)
  }
}

fun SemanticsNodeInteractionsProvider.onTripCard(tripId: String): SemanticsNodeInteraction =
    onNodeWithTag(AvailableTripsScreenTestTags.tripCard(tripId))

/** Asserts the card of [tripId] is displayed and shows [storeName]. */
fun SemanticsNodeInteractionsProvider.assertTripCard(
    tripId: String,
    storeName: String,
): SemanticsNodeInteraction = onTripCard(tripId).assertIsDisplayed().assert(hasText(storeName))

fun SemanticsNodeInteractionsProvider.assertTripCard(trip: Trip) =
    assertTripCard(trip.id, trip.store.name)
