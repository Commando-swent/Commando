package com.android.sample.data.repository

// AI assistance: Claude Code for trip transitions; OpenAI Codex for live feeds.
import com.android.sample.model.Location
import com.android.sample.model.NewTrip
import com.android.sample.model.OrderStatus
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.model.TripTransition
import com.android.sample.model.blocksTripCompletion
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.*

/** In-memory [TripRepository] for tests and previews. */
class FakeTripRepository(
    private val currentUserId: String,
    private val now: () -> Instant = Instant::now,
    initialTrips: List<Trip> = emptyList(),
    /** Statuses of the orders of a trip, consulted only to complete it; none by default. */
    private val tripOrderStatuses: suspend (tripId: String) -> List<OrderStatus> = { emptyList() },
) : TripRepository {

  private val changes = MutableStateFlow(0L)

  override fun observeMyTrips() = changes.map { getMyTrips() }

  override fun observeUpcomingTrips() = changes.map { getUpcomingTrips() }

  private val trips = initialTrips.associateByTo(linkedMapOf(), Trip::id)

  var forcedError: TripError? = null

  override suspend fun publishTrip(newTrip: NewTrip): TripResult<Trip> {
    forcedError?.let {
      return TripResult.Error(it)
    }

    val createdAt = now()
    if (!newTrip.isValid(createdAt)) {
      return TripResult.Error(TripError.InvalidData)
    }

    val trip =
        Trip(
            id = UUID.randomUUID().toString(),
            ownerId = currentUserId,
            store = newTrip.store,
            scheduledAt = newTrip.scheduledAt,
            handoffLocation = newTrip.handoffLocation,
            status = TripStatus.PUBLISHED,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    trips[trip.id] = trip
    changes.value += 1
    return TripResult.Success(trip)
  }

  override suspend fun getTripById(tripId: String): TripResult<Trip> {
    forcedError?.let {
      return TripResult.Error(it)
    }
    return trips[tripId]?.let { TripResult.Success(it) } ?: TripResult.Error(TripError.NotFound)
  }

  override suspend fun getUpcomingTrips(): TripResult<List<Trip>> {
    forcedError?.let {
      return TripResult.Error(it)
    }
    val currentTime = now()
    return TripResult.Success(
        trips.values
            .filter { it.status == TripStatus.PUBLISHED && it.scheduledAt > currentTime }
            .sortedBy(Trip::scheduledAt)
    )
  }

  override suspend fun getMyTrips(): TripResult<List<Trip>> {
    forcedError?.let {
      return TripResult.Error(it)
    }
    return TripResult.Success(trips.values.filter { it.ownerId == currentUserId })
  }

  override suspend fun transitionTrip(
      tripId: String,
      transition: TripTransition,
  ): TripResult<Trip> {
    forcedError?.let {
      return TripResult.Error(it)
    }
    if (!tripId.isDocumentId()) return TripResult.Error(TripError.InvalidData)
    val trip = trips[tripId] ?: return TripResult.Error(TripError.NotFound)
    trip.transitionPrecheck(currentUserId, transition)?.let {
      return it
    }
    if (
        transition == TripTransition.COMPLETE &&
            tripOrderStatuses(tripId).any(OrderStatus::blocksTripCompletion)
    ) {
      return TripResult.Error(TripError.OrdersStillOpen)
    }
    val updated = trip.copy(status = transition.target, updatedAt = now())
    trips[tripId] = updated
    changes.value += 1
    return TripResult.Success(updated)
  }

  private fun NewTrip.isValid(currentTime: Instant): Boolean =
      store.isValid() && handoffLocation.isValid() && scheduledAt > currentTime

  private fun Location.isValid(): Boolean =
      name.isNotBlank() &&
          latitude.isFinite() &&
          latitude in -90.0..90.0 &&
          longitude.isFinite() &&
          longitude in -180.0..180.0
}
