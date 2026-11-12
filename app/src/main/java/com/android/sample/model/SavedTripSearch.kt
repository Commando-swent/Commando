package com.android.sample.model

// AI assistance: Claude Code.
import java.time.Instant

/** Longest accepted preset name, after trimming. */
const val SAVED_SEARCH_NAME_MAX_LENGTH = 40

/**
 * A requester's named search preset. The delivery point is deliberately not part of it, so applying
 * a preset never changes the selected point.
 */
data class SavedTripSearch(
    val id: String,
    val name: String,
    val criteria: TripSearchCriteria,
    val createdAt: Instant,
)

/** The trimmed preset name, or null when it is blank or longer than the maximum. */
fun normalizedSearchName(name: String): String? =
    name.trim().takeIf { it.isNotEmpty() && it.length <= SAVED_SEARCH_NAME_MAX_LENGTH }
