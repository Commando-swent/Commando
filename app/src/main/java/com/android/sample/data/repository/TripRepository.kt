package com.android.sample.data.repository

// AI assistance: Claude Code for the trip transition (#233).
import com.android.sample.model.NewTrip
import com.android.sample.model.Trip
import com.android.sample.model.TripTransition
import com.android.sample.model.blocksTripCompletion

interface TripRepository {

  suspend fun publishTrip(newTrip: NewTrip): TripResult<Trip>

  suspend fun getTripById(tripId: String): TripResult<Trip>

  suspend fun getUpcomingTrips(): TripResult<List<Trip>>

  suspend fun getMyTrips(): TripResult<List<Trip>>

  /**
   * Applies [transition] to the current user's trip [tripId], checked against the latest stored
   * status, and returns the stored trip. A repeat (trip already at the target status) succeeds
   * without writing. COMPLETE is refused with [TripError.OrdersStillOpen] while an order of the
   * trip still [blocksTripCompletion].
   */
  suspend fun transitionTrip(tripId: String, transition: TripTransition): TripResult<Trip>
}

/**
 * Outcome decided from the stored trip alone, or null when [transition] still has to be written.
 * Checks, in order: owner is [callerId], already at target (success), at source.
 */
internal fun Trip.transitionPrecheck(
    callerId: String,
    transition: TripTransition,
): TripResult<Trip>? =
    when {
      ownerId != callerId -> TripResult.Error(TripError.PermissionDenied)
      status == transition.target -> TripResult.Success(this)
      status != transition.source -> TripResult.Error(TripError.InvalidTransition)
      else -> null
    }
