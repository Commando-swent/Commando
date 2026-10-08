package com.android.sample.ui.trips

// AI assistance: Claude Code.
import com.android.sample.model.NewTrip
import java.time.Instant

/** A validated trip creation form, independent of how trips are stored. */
data class TripDraft(
    val store: String,
    val scheduledAt: Instant,
    val handoffLocation: String,
    val maxOrders: Int,
)

/**
 * The only place where the form is turned into the repository model.
 *
 * Returns null until [NewTrip] can hold a draft without losing or inventing data. It currently
 * lacks the maximum number of orders, and requires store and handoff coordinates the form does not
 * collect.
 */
// TODO(#9): build the NewTrip once the model has maxOrders and coordinates have a source.
fun TripDraft.toNewTrip(): NewTrip? = null
