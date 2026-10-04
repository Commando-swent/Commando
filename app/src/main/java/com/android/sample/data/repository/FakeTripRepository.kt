package com.android.sample.data.repository

import com.android.sample.model.Location
import com.android.sample.model.NewTrip
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import java.time.Instant
import java.util.UUID

class FakeTripRepository(
    private val currentUserId: String,
    private val now: () -> Instant = Instant::now,
    initialTrips: List<Trip> = emptyList(),
) : TripRepository {

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

  private fun NewTrip.isValid(currentTime: Instant): Boolean =
      store.isValid() && handoffLocation.isValid() && scheduledAt > currentTime

  private fun Location.isValid(): Boolean =
      name.isNotBlank() &&
          latitude.isFinite() &&
          latitude in -90.0..90.0 &&
          longitude.isFinite() &&
          longitude in -180.0..180.0
}
