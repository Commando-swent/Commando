package com.commando.app.model

// AI assistance: Claude Code.
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TripEarningsTest {
  private val createdAt = Instant.parse("2026-11-13T12:00:00Z")
  private val milk = OrderItem("milk", "Milk", unitPriceCents = 190, quantity = 2)

  private fun order(
      id: String,
      status: OrderStatus,
      commissionCents: Int = 300,
      tripId: String = TRIP_ID,
  ) = Order(id, tripId, "requester", listOf(milk), commissionCents, status, createdAt)

  @Test
  fun onlyDeliveredOrdersOfTheTripCountAndOnlyTheirCommission() {
    val orders =
        listOf(order("delivered-1", OrderStatus.DELIVERED, commissionCents = 250)) +
            order("delivered-2", OrderStatus.DELIVERED, commissionCents = 400) +
            OrderStatus.entries
                .filter { it != OrderStatus.DELIVERED }
                .map { order("open-$it", it, commissionCents = 999) } +
            order("other-trip", OrderStatus.DELIVERED, commissionCents = 999, tripId = "other")

    // Item prices are the requester's shopping, not the commando's earnings.
    assertEquals(TripEarnings(650L, 2), tripEarnings(TRIP_ID, orders))
  }

  @Test
  fun tripWithoutDeliveredOrdersEarnsNothing() {
    assertEquals(TripEarnings(0L, 0), tripEarnings(TRIP_ID, emptyList()))
    assertEquals(
        TripEarnings(0L, 0),
        tripEarnings(TRIP_ID, listOf(order("pending", OrderStatus.PENDING))),
    )
  }

  @Test
  fun largeCommissionsAddUpWithoutOverflow() {
    val orders = (1..3).map { order("big-$it", OrderStatus.DELIVERED, Int.MAX_VALUE) }

    assertEquals(TripEarnings(3L * Int.MAX_VALUE, 3), tripEarnings(TRIP_ID, orders))
  }

  private companion object {
    const val TRIP_ID = "trip-1"
  }
}
