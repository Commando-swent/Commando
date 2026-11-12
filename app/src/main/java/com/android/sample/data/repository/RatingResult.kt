package com.android.sample.data.repository

// AI assistance: OpenAI Codex.

/** Rating failures without exposing another user's persisted order or feedback. */
sealed class RatingError {
  /** No authenticated session, or the session ended or changed during the operation. */
  data object NotAuthenticated : RatingError()

  /** An order or trip required for the operation could not be found. */
  data object NotFound : RatingError()

  /** The caller is not authorized, including attempts to rate their own trip. */
  data object PermissionDenied : RatingError()

  /** Invalid input or malformed data returned by a dependency. */
  data object InvalidData : RatingError()

  /** The authorized requester's order has not reached DELIVERED. */
  data object OrderNotDelivered : RatingError()

  /** Feedback already exists for this order; the original remains unchanged. */
  data object AlreadyRated : RatingError()

  /** An unexpected failure prevented the operation from completing. */
  data object Unknown : RatingError()
}

/** Typed outcome of a rating operation; coroutine cancellation is propagated separately. */
sealed class RatingResult<out T> {
  /** Repository-confirmed data, including null for an authorized missing-rating read. */
  data class Success<T>(val data: T) : RatingResult<T>()

  /** A domain failure without a successful payload. */
  data class Error(val error: RatingError) : RatingResult<Nothing>()
}
