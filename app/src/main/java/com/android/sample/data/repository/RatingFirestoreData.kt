package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.*
import com.google.firebase.Timestamp

internal const val RATINGS = "ratings"
internal const val SUMMARIES = "ratingSummaries"
internal val RATING_SCORES = 1..5

/** Domain failure transported through transaction callbacks; never an SDK error code. */
internal class RatingFailure(val error: RatingError) : RuntimeException()

internal fun requireRating(condition: Boolean, error: RatingError = RatingError.InvalidData) {
  if (!condition) throw RatingFailure(error)
}

internal data class RatingContext(
    val tripId: String,
    val requesterId: String,
    val commandoId: String,
)

/** Authorizes persisted relationships before decoding feedback and matching all identities. */
internal fun RatingTransaction.readRating(
    orderId: String,
    uid: String,
    submitting: Boolean = false,
): Rating? {
  val context = context(orderId, uid, submitting)
  val rating = get(RATINGS, orderId)?.rating()
  requireRating(
      rating == null ||
          (rating.tripId == context.tripId &&
              rating.requesterId == context.requesterId &&
              rating.commandoId == context.commandoId)
  )
  return rating
}

/** Resolves persisted participants; submission eligibility precedes trip lookup. */
internal fun RatingTransaction.context(
    id: String,
    uid: String,
    submitting: Boolean = false,
): RatingContext {
  val order = get("orders", id)?.data ?: throw RatingFailure(RatingError.NotFound)
  val tripId = order["tripId"] as? String
  val requester = order["requesterId"] as? String
  requireRating(tripId?.isDocumentId() == true && !requester.isNullOrBlank())
  if (submitting) {
    requireRating(requester == uid, RatingError.PermissionDenied)
    requireRating(order["status"] == OrderStatus.DELIVERED.name, RatingError.OrderNotDelivered)
  }
  val trip = get("trips", tripId!!)?.data ?: throw RatingFailure(RatingError.NotFound)
  val owner = trip["ownerId"] as? String
  requireRating(!owner.isNullOrBlank())
  requireRating(uid == requester || uid == owner, RatingError.PermissionDenied)
  requireRating(!submitting || requester != owner, RatingError.PermissionDenied)
  return RatingContext(tripId, requester!!, owner!!)
}

private fun Any?.integer(): Long? =
    when (this) {
      is Long -> this
      is Int -> toLong()
      else -> null
    }

/** Decodes integral counters; an absent summary represents an unrated Commando. */
internal fun FirestoreDocument?.summary(id: String): Pair<Int, Long> {
  if (this == null) return 0 to 0L
  val count = data["ratingCount"].integer() ?: 0
  val total = data["scoreTotal"].integer() ?: 0
  requireRating(
      this.id == id &&
          count in 1..Int.MAX_VALUE.toLong() &&
          total in count..count * RATING_SCORES.last &&
          (data["lastOrderId"] as? String)?.isDocumentId() == true,
  )
  return count.toInt() to total
}

/** Rejects malformed persisted feedback rather than supplying identity or score defaults. */
internal fun FirestoreDocument.rating(): Rating {
  val trip = data["tripId"] as? String ?: invalidRating()
  val requester = data["requesterId"] as? String ?: invalidRating()
  val owner = data["commandoId"] as? String ?: invalidRating()
  val score = data["score"].integer() ?: invalidRating()
  val comment = data["comment"]
  val created = data["createdAt"] as? Timestamp ?: invalidRating()
  requireRating(
      data["orderId"] == id &&
          trip.isDocumentId() &&
          requester.isNotBlank() &&
          owner.isNotBlank() &&
          requester != owner &&
          score in 1L..RATING_SCORES.last.toLong() &&
          (comment == null ||
              (comment is String &&
                  comment.isNotBlank() &&
                  comment.length <= MAX_RATING_COMMENT_LENGTH)),
  )
  return Rating(id, trip, requester, owner, score.toInt(), comment as String?, created.toInstant())
}

private fun invalidRating(): Nothing = throw RatingFailure(RatingError.InvalidData)
