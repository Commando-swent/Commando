package com.android.sample.model

// AI assistance: Claude Code.

/**
 * Status change a trip owner may request. Every other status change is rejected by the repositories
 * and by the Firestore rules.
 *
 * @property source Status the stored trip must have for the change to be written.
 * @property target Status written by the change.
 */
enum class TripTransition(val source: TripStatus, val target: TripStatus) {
  /** Stops new orders: the trip leaves discovery and the order rules refuse new orders. */
  CLOSE(TripStatus.PUBLISHED, TripStatus.IN_PROGRESS),
  /** Ends the trip once no order [blocksTripCompletion]. */
  COMPLETE(TripStatus.IN_PROGRESS, TripStatus.COMPLETED),
}

/**
 * True for PENDING, ACCEPTED and READY_FOR_HANDOFF: the order still needs action, so its trip
 * cannot be completed.
 */
fun OrderStatus.blocksTripCompletion(): Boolean =
    when (this) {
      OrderStatus.PENDING,
      OrderStatus.ACCEPTED,
      OrderStatus.READY_FOR_HANDOFF -> true
      OrderStatus.REJECTED,
      OrderStatus.CANCELLED,
      OrderStatus.DELIVERED -> false
    }
