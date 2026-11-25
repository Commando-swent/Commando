package com.commando.app.model

// AI assistance: Claude Code (trip cancellation: #449).

/**
 * Status change a trip owner may request. Every other status change is rejected by the repositories
 * and by the Firestore rules.
 *
 * @property source Status the stored trip must have for the change to be written.
 * @property target Status written by the change.
 * @property requiresSettledOrders True when the change is refused while an order of the trip
 *   [blocksTripCompletion].
 */
enum class TripTransition(
    val source: TripStatus,
    val target: TripStatus,
    val requiresSettledOrders: Boolean,
) {
  /** Stops new orders: the trip leaves discovery and the order rules refuse new orders. */
  CLOSE(TripStatus.PUBLISHED, TripStatus.IN_PROGRESS, requiresSettledOrders = false),
  /** Ends the trip once no order [blocksTripCompletion]. */
  COMPLETE(TripStatus.IN_PROGRESS, TripStatus.COMPLETED, requiresSettledOrders = true),
  /**
   * Withdraws a trip that has not started, once no order [blocksTripCompletion]: it leaves
   * discovery and new orders are refused. Kept after [CLOSE] so that [CLOSE] stays the main action
   * of a PUBLISHED trip.
   */
  CANCEL(TripStatus.PUBLISHED, TripStatus.CANCELLED, requiresSettledOrders = true),
}

/**
 * True for PENDING, ACCEPTED and READY_FOR_HANDOFF: the order still needs action, so its trip
 * cannot be completed or cancelled.
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
