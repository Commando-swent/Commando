package com.commando.app.model

// AI assistance: Claude Code.

/**
 * What a commando earned on one trip: [totalCents] is the sum of the commissions of its
 * [deliveredCount] delivered orders, in CHF cents.
 */
data class TripEarnings(val totalCents: Long, val deliveredCount: Int)

/**
 * Earnings of [tripId] from [orders]. Only [OrderStatus.DELIVERED] orders of that trip count:
 * pending, accepted, ready, rejected and cancelled orders bring nothing yet or never will. The sum
 * is a Long, so many large commissions cannot overflow.
 */
fun tripEarnings(tripId: String, orders: List<Order>): TripEarnings {
  val delivered = orders.filter { it.tripId == tripId && it.status == OrderStatus.DELIVERED }
  return TripEarnings(delivered.sumOf { it.commissionCents.toLong() }, delivered.size)
}
