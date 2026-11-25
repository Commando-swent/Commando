package com.commando.app.data.repository

// AI assistance: Claude Code for trip transitions and idempotent publication (#394); OpenAI Codex
// for live feed expiry.
import com.commando.app.model.NewTrip
import com.commando.app.model.Trip
import com.commando.app.model.TripStatus
import com.commando.app.model.TripTransition
import com.commando.app.model.blocksTripCompletion
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

interface TripRepository {
  /** Observe confirmed upcoming trips; null means unconfirmed. Unsupported adapters fail closed. */
  fun observeUpcomingTrips(): Flow<TripResult<List<Trip>>?> = flow {
    error("Live trips unsupported")
  }

  /**
   * Publishes [newTrip] under the caller's [tripId], at most once. The caller creates one ID per
   * publication intent and reuses it on every retry, never a new ID per attempt. A trip already
   * stored under [tripId] by the current user with the same store, departure and handoff is
   * returned as stored now, without writing, even after its departure. Any other stored trip is
   * left unchanged: PermissionDenied for another owner, InvalidData for different fields. Offline
   * publication fails instead of being queued. NetworkError, Unknown or cancellation may follow a
   * server commit: reconcile by retrying with the same ID.
   */
  suspend fun publishTrip(tripId: String, newTrip: NewTrip): TripResult<Trip>

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

/** Removes quiet departures too; a new snapshot cancels the previous expiry timer. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun Flow<TripResult<List<Trip>>?>.expireUpcomingTrips(
    now: () -> Instant,
): Flow<TripResult<List<Trip>>?> = transformLatest { result ->
  if (result !is TripResult.Success) {
    emit(result)
    return@transformLatest
  }
  while (true) {
    val currentTime = now()
    val upcoming =
        result.data.filter { it.status == TripStatus.PUBLISHED && it.scheduledAt > currentTime }
    emit(TripResult.Success(upcoming))
    val nextDeparture = upcoming.minOfOrNull(Trip::scheduledAt) ?: return@transformLatest
    // Wait at least one millisecond so sub-millisecond deadlines cannot busy-loop.
    delay(Duration.between(currentTime, nextDeparture).toMillis().coerceAtLeast(1))
  }
}
