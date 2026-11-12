package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.MAX_RATING_COMMENT_LENGTH
import com.android.sample.model.NewRating
import com.android.sample.model.OrderStatus
import com.android.sample.model.Rating
import com.android.sample.model.RatingSummary

/**
 * Submits immutable order feedback and provides authorized rating reads.
 *
 * Implementations enforce authentication and authorization independently of UI eligibility. Session
 * identity must be checked after suspended lookups and before mutation or returning a result; an
 * ended or changed session returns [RatingError.NotAuthenticated]. Cancellation is rethrown, never
 * converted to a rating error, and cannot undo an already committed write.
 */
interface RatingRepository {
  /**
   * Creates the sole rating for the authenticated requester's persisted [OrderStatus.DELIVERED]
   * order. Trip completion is not required. Requester and trip IDs come from that order; Commando
   * identity comes from its persisted trip owner. Self-rating is denied.
   *
   * Validates the order document ID and an integer score in 1..5. Null, blank and whitespace-only
   * comments normalize to null before length validation. Other comments retain their exact text and
   * must have [String.length] at most [MAX_RATING_COMMENT_LENGTH].
   *
   * After successful input validation and authorization, duplicate submissions, including identical
   * input, return [RatingError.AlreadyRated] without overwriting the original. Concurrent
   * submissions must not create multiple ratings. Ratings cannot be edited after submission.
   *
   * @return The stored rating or a typed failure. Missing order/trip returns
   *   [RatingError.NotFound]; an authorized non-delivered order returns
   *   [RatingError.OrderNotDelivered].
   * @throws kotlinx.coroutines.CancellationException When the calling coroutine is cancelled.
   */
  suspend fun submitRating(input: NewRating): RatingResult<Rating>

  /**
   * Reads feedback for a valid order document ID, only for its authenticated requester or
   * associated trip owner. Authorization uses persisted relationships and precedes revealing
   * whether a rating exists; unrelated callers receive [RatingError.PermissionDenied].
   *
   * @return Success with null for an authorized order without a rating, or [RatingError.NotFound]
   *   when a required order or trip is missing. Invalid IDs return [RatingError.InvalidData].
   * @throws kotlinx.coroutines.CancellationException When the calling coroutine is cancelled.
   */
  suspend fun getRatingForOrder(orderId: String): RatingResult<Rating?>

  /**
   * Returns the count and unrounded arithmetic mean of all ratings for [commandoId]. Any
   * authenticated user may read aggregates; individual requester identities are not exposed.
   *
   * @return Zero count and null average when no ratings exist. Blank Commando identities return
   *   [RatingError.InvalidData]; missing authentication returns [RatingError.NotAuthenticated].
   * @throws kotlinx.coroutines.CancellationException When the calling coroutine is cancelled.
   */
  suspend fun getRatingSummary(commandoId: String): RatingResult<RatingSummary>
}
