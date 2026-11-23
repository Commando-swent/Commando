package com.android.sample.ui.orders

import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.data.repository.OrderError
import com.commando.app.data.repository.OrderResult
import com.commando.app.model.OrderItem
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
  fun recoveryTransfersQuantitiesPricesAndCommissionToTheSameChainOnlyOnce() {
    val shared = OrderDraftState("own-trip")
    shared.addItem(milk)
    shared.updateCommission(150)
    shared.prepareTripRecovery("migros")
    shared.startTrip("alternative")
    shared.recoverForTrip("old-stale-trip", "migros")
    assertTrue(shared.draft.value.items.isEmpty())
    shared.recoverForTrip("alternative", "migros")
    assertEquals(listOf(milk), shared.draft.value.items)
    assertEquals(150, shared.draft.value.commissionCents)
    assertEquals("alternative", shared.draft.value.tripId)
    shared.startTrip("next")
    shared.recoverForTrip("next", "migros")
    assertTrue(shared.draft.value.items.isEmpty())
  }

  @Test
  fun incompatibleChainConsumesRecoveryWithoutCopyingProducts() {
    val shared = OrderDraftState("own-trip")
    shared.addItem(milk)
    shared.prepareTripRecovery("migros")
    shared.startTrip("coop-trip")
    shared.recoverForTrip("coop-trip", "coop")
    assertTrue(shared.draft.value.items.isEmpty())
    shared.startTrip("migros-trip")
    shared.recoverForTrip("migros-trip", "migros")
    assertTrue(shared.draft.value.items.isEmpty())
  }

  @Test
  fun clearingBasketAlsoClearsPendingRecovery() {
    val shared = OrderDraftState("own-trip")
    shared.addItem(milk)
    shared.prepareTripRecovery("migros")
    shared.clear()
    shared.startTrip("alternative")
    shared.recoverForTrip("alternative", "migros")
    assertTrue(shared.draft.value.items.isEmpty())
  }

  @Test
  fun unsupportedStoresCannotTransferABasket() {
    val shared = OrderDraftState("own-trip")
    shared.addItem(milk)
    shared.prepareTripRecovery(null)
    shared.startTrip("alternative")
    shared.recoverForTrip("alternative", null)
    assertTrue(shared.draft.value.items.isEmpty())
  }

  @Test
  fun editingAnExistingOrderCannotArmTripRecovery() {
    val shared = OrderDraftState("trip", com.commando.app.model.OrderDraft("trip", listOf(milk)), 3)
    shared.prepareTripRecovery("migros")
    shared.startTrip("alternative")
    shared.recoverForTrip("alternative", "migros")
    assertTrue(shared.draft.value.items.isEmpty())
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
    assertEquals(OrderResult.Error(OrderError.Unknown), fake.createOrder(shared.draft.value))
    assertEquals(listOf(milk), shared.draft.value.items)
    fake.forcedError = null
    val retry = fake.createOrder(shared.draft.value)
    assertTrue("Retry must succeed before clearing the draft", retry is OrderResult.Success)
    assertEquals(listOf(milk), shared.draft.value.items)
    shared.clear()
    assertTrue(shared.draft.value.items.isEmpty())
    assertEquals("trip-1", shared.draft.value.tripId)
  }
}
