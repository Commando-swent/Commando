package com.android.sample.ui.trips

// AI assistance: Claude Code.
import com.android.sample.model.NewTrip
import java.time.Instant

/** A validated trip creation form, independent of how trips are stored. */
data class TripDraft(
    val stores: List<String>,
    val scheduledAt: Instant,
    val handoffLocation: String,
    val maxOrders: Int,
)

/**
 * The only place where the form is turned into the repository model.
 *
 * Returns null until [NewTrip] can hold a draft without losing or inventing data. It currently
 * lacks the maximum number of orders, has a single store, and requires coordinates the form does
 * not collect.
 */
// TODO(#9): build the NewTrip once the model supports maxOrders, several stores and a source of
//  coordinates.
fun TripDraft.toNewTrip(): NewTrip? = null
