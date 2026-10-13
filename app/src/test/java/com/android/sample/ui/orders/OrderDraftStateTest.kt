package com.android.sample.ui.orders

import com.android.sample.data.repository.FakeOrderRepository
import com.android.sample.data.repository.OrderError
import com.android.sample.model.OrderItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrderDraftStateTest {
  private val milk = OrderItem("milk", "Milk", 220, 2)

  @Test
  fun bothConsumersObserveQuantityChangesWithoutDuplicatingRows() {
    val shared = OrderDraftState("trip-1")
    val catalogue = shared.draft
    val review = shared.draft
    shared.addItem(milk)
    shared.addItem(milk.copy(quantity = 3))
    assertEquals(listOf(milk.copy(quantity = 3)), review.value.items)
    shared.updateQuantity("milk", 4)
    shared.startTrip("trip-1")
    assertEquals(4, catalogue.value.items.single().quantity)
    assertSame(catalogue, review)
    assertThrows(UnsupportedOperationException::class.java) {
      (review.value.items as MutableList).clear()
    }
  }

  @Test
  fun zeroRemovesAndChangingTripsResetsSelectionAndCommission() {
    val shared = OrderDraftState("trip-1")
    shared.addItem(milk)
    shared.updateQuantity("unknown", 8)
    assertEquals(listOf(milk), shared.draft.value.items)
    shared.updateQuantity("milk", 0)
    assertTrue(shared.draft.value.items.isEmpty())
    shared.addItem(milk)
    shared.updateCommission(150)
    shared.startTrip("trip-2")
    assertEquals("trip-2", shared.draft.value.tripId)
    assertTrue(shared.draft.value.items.isEmpty())
    assertEquals(0, shared.draft.value.commissionCents)
  }

  @Test
  fun invalidEditsPreserveTheDraft() {
    val shared = OrderDraftState("trip-1")
    shared.addItem(milk)
    val before = shared.draft.value
    assertThrows(IllegalArgumentException::class.java) { shared.startTrip(" ") }
    assertThrows(IllegalArgumentException::class.java) { shared.updateQuantity("milk", -1) }
    assertThrows(IllegalArgumentException::class.java) { shared.updateCommission(-1) }
    assertThrows(IllegalArgumentException::class.java) { shared.addItem(milk.copy(quantity = 0)) }
    assertEquals(before, shared.draft.value)
  }

  @Test
  fun repositoryLeavesDraftIntactAndCallerClearsOnlyAfterSuccess() = runTest {
    val shared = OrderDraftState("trip-1")
    val fake = FakeOrderRepository({ "requester" })
    shared.addItem(milk)
    fake.forcedError = OrderError.Unknown
    fake.createOrder(shared.draft.value)
    assertEquals(listOf(milk), shared.draft.value.items)
    fake.forcedError = null
    fake.createOrder(shared.draft.value)
    assertEquals(listOf(milk), shared.draft.value.items)
    shared.clear()
    assertTrue(shared.draft.value.items.isEmpty())
    assertEquals("trip-1", shared.draft.value.tripId)
  }
}
