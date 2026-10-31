package com.android.sample.model

// AI assistance: Claude Code.
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingChecklistTest {
  private val tripId = "trip-1"
  private val milk = OrderItem("milk", "Milk", 150, 2)
  private val bread = OrderItem("bread", "Bread", 300, 1)

  private fun order(
      id: String,
      vararg items: OrderItem,
      status: OrderStatus = OrderStatus.ACCEPTED,
      trip: String = tripId,
      createdAtSeconds: Long = 0,
  ) =
      Order(
          id = id,
          tripId = trip,
          requesterId = "requester-$id",
          items = items.toList(),
          commissionCents = 0,
          status = status,
          createdAt = Instant.ofEpochSecond(createdAtSeconds),
      )

  private fun item(productId: String, name: String, quantity: Long, purchased: Boolean = false) =
      ShoppingChecklistItem(productId, name, quantity, purchased)

  @Test
  fun groupsAcceptedProductsByIdAndSumsQuantities() {
    val orders = listOf(order("a", milk, bread), order("b", milk.copy(quantity = 3)))

    assertEquals(
        listOf(item("bread", "Bread", 1), item("milk", "Milk", 5)),
        buildShoppingChecklist(tripId, orders, emptyMap()),
    )
  }

  @Test
  fun onlyAcceptedOrdersOfTheTripContribute() {
    val orders =
        listOf(
            order("pending", milk, status = OrderStatus.PENDING),
            order("rejected", milk, status = OrderStatus.REJECTED),
            order("other-trip", milk, trip = "trip-2"),
            order("accepted", bread),
        )

    assertEquals(listOf(item("bread", "Bread", 1)), buildShoppingChecklist(tripId, orders, mapOf()))
    assertEquals(setOf(OrderStatus.ACCEPTED), SHOPPING_ELIGIBLE_STATUSES)
  }

  @Test
  fun productsWithTheSameNameButDifferentIdsAreNotMerged() {
    val coopMilk = OrderItem("coop-milk", "Milk", 140, 1)

    assertEquals(
        listOf(item("coop-milk", "Milk", 1), item("milk", "Milk", 2)),
        buildShoppingChecklist(tripId, listOf(order("a", milk), order("b", coopMilk)), mapOf()),
    )
  }

  @Test
  fun resultIsIndependentOfInputOrderAndUsesTheOldestName() {
    val renamed = milk.copy(name = "Whole milk")
    val orders = listOf(order("new", renamed, createdAtSeconds = 2), order("old", milk))

    val checklist = buildShoppingChecklist(tripId, orders, emptyMap())

    assertEquals(listOf(item("milk", "Milk", 4)), checklist)
    assertEquals(checklist, buildShoppingChecklist(tripId, orders.reversed(), emptyMap()))
  }

  @Test
  fun progressIsKeptOnlyWhileTheCheckedQuantityCoversTheRequirement() {
    val purchased = mapOf("milk" to 2L, "bread" to 1L)
    val before = buildShoppingChecklist(tripId, listOf(order("a", milk, bread)), purchased)
    assertTrue(before.all { it.purchased })

    // A newly accepted order adds milk: only milk is unchecked, bread keeps its progress.
    val orders = listOf(order("a", milk, bread), order("b", milk.copy(quantity = 1)))
    assertEquals(
        listOf(item("bread", "Bread", 1, purchased = true), item("milk", "Milk", 3)),
        buildShoppingChecklist(tripId, orders, purchased),
    )
  }

  @Test
  fun emptyWhenNoOrderIsEligible() {
    assertTrue(buildShoppingChecklist(tripId, emptyList(), mapOf("milk" to 1L)).isEmpty())
  }
}
