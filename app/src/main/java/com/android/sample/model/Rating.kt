package com.android.sample.model

// AI assistance: OpenAI Codex.
import java.time.Instant

/** Maximum length of a nonblank rating comment, measured by [String.length]. */
const val MAX_RATING_COMMENT_LENGTH = 250

/**
 * Immutable feedback submitted once for a delivered order; [orderId] is its unique key.
 *
 * Repositories derive [tripId] and [requesterId] from the persisted order and [commandoId] from its
 * trip owner. [score] is an integer from 1 through 5. A missing, blank or whitespace-only comment
 * is stored as null; other comments retain their text and must not exceed
 * [MAX_RATING_COMMENT_LENGTH]. [createdAt] is assigned by the repository, never by caller input.
 * Validation and comment normalization belong to repository implementations.
 */
data class Rating(
    val orderId: String,
    val tripId: String,
    val requesterId: String,
    val commandoId: String,
    val score: Int,
    val comment: String?,
    val createdAt: Instant,
)

/**
 * Caller input without trusted identities, trip association or creation time.
 *
 * Repositories validate [score] in 1..5 and normalize blank or whitespace-only [comment] to null
 * before checking its length against [MAX_RATING_COMMENT_LENGTH]. Nonblank text is preserved.
 */
data class NewRating(
    val orderId: String,
    val score: Int,
    val comment: String? = null,
)

/**
 * Aggregate of all ratings for [commandoId], with an unrounded arithmetic [averageScore]. An
 * unrated Commando has [ratingCount] zero and [averageScore] null.
 */
data class RatingSummary(
    val commandoId: String,
    val ratingCount: Int,
    val averageScore: Double?,
)
