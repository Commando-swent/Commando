package com.android.sample.data.repository

// AI assistance: Claude Code for the trip transition errors (#233).
sealed class TripError {
  data object NotFound : TripError()

  data object NetworkError : TripError()

  data object PermissionDenied : TripError()

  data object InvalidData : TripError()

  data object Unknown : TripError()

  /**
   * The stored status is neither the transition's source nor its target, e.g. completing a
   * PUBLISHED trip.
   */
  data object InvalidTransition : TripError()

  /** Completion refused: an order of the trip is still PENDING, ACCEPTED or READY_FOR_HANDOFF. */
  data object OrdersStillOpen : TripError()
}

sealed class TripResult<out T> {
  data class Success<T>(val data: T) : TripResult<T>()

  data class Error(val error: TripError) : TripResult<Nothing>()
}
