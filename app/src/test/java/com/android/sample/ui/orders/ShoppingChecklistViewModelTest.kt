package com.android.sample.ui.orders

// AI assistance: Claude Code.
import com.android.sample.MainDispatcherRule
import com.android.sample.data.repository.*
import com.android.sample.model.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingChecklistViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val now = Instant.parse("2026-10-06T12:00:00Z")
  private val place = Location("Migros", 46.52, 6.63)
  private val trip = Trip("trip-1", "owner", place, now, place, TripStatus.PUBLISHED, now, now)
  private val milk = OrderItem("milk", "Milk", unitPriceCents = 190, quantity = 2)
  private val bread = OrderItem("bread", "Bread", unitPriceCents = 300, quantity = 1)
  private var sessionUser: String? = "owner"
  private val trips = FakeTripRepository("owner", now = { now }, initialTrips = listOf(trip))
  private val checklist = FakeShoppingChecklistRepository { sessionUser }

  private fun order(id: String, status: OrderStatus, vararg items: OrderItem) =
      Order(id, trip.id, "requester", items.toList(), 0, status, now)

  private val orders =
      FakeOrderRepository(
          { sessionUser },
          listOf(
              order("a", OrderStatus.ACCEPTED, milk, bread),
              order("p", OrderStatus.PENDING, milk),
          ),
          tripRepository = trips,
      )

  private fun item(product: OrderItem, purchased: Boolean = false) =
      ShoppingChecklistItem(product.productId, product.name, product.quantity.toLong(), purchased)

  private fun content(vararg items: ShoppingChecklistItem) =
      ShoppingChecklistUiState.Content(items.toList())

  private fun TestScope.loaded(repository: FakeOrderRepository = orders) =
      ShoppingChecklistViewModel(repository, checklist, trip.id).also {
        it.refresh()
        advanceUntilIdle()
      }

  @Test
  fun loadsAcceptedProductsWithTheirSavedProgress() = runTest {
    checklist.setPurchased(trip.id, item(bread), true)
    val viewModel = ShoppingChecklistViewModel(orders, checklist, trip.id)
    assertEquals(ShoppingChecklistUiState.Loading, viewModel.uiState.value)
    viewModel.refresh()
    advanceUntilIdle()

    assertEquals(content(item(bread, purchased = true), item(milk)), viewModel.uiState.value)
  }

  @Test
  fun emptyWithoutAcceptedOrdersAndErrorsAreRetryable() = runTest {
    val noOrders = FakeOrderRepository({ sessionUser }, tripRepository = trips)
    assertEquals(ShoppingChecklistUiState.Empty, loaded(noOrders).uiState.value)

    orders.forcedError = OrderError.Unknown
    val viewModel = loaded()
    assertEquals(ShoppingChecklistUiState.Error(OrderError.Unknown), viewModel.uiState.value)
    orders.forcedError = null
    checklist.forcedError = OrderError.PermissionDenied
    viewModel.refresh()
    advanceUntilIdle()
    val denied = ShoppingChecklistUiState.Error(OrderError.PermissionDenied)
    assertEquals(denied, viewModel.uiState.value)
    checklist.forcedError = null
    viewModel.refresh()
    advanceUntilIdle()
    assertEquals(content(item(bread), item(milk)), viewModel.uiState.value)
  }

  @Test
  fun itemChangesOnlyAfterTheSaveAndIsRestoredByANewViewModel() = runTest {
    val viewModel = loaded()
    viewModel.setPurchased(item(milk), true)
    assertEquals(content(item(bread), item(milk)), viewModel.uiState.value)
    advanceUntilIdle()
    assertEquals(content(item(bread), item(milk, purchased = true)), viewModel.uiState.value)
    assertEquals(viewModel.uiState.value, loaded().uiState.value)
  }

  @Test
  fun failedSaveKeepsTheItemUntilASaveSucceeds() = runTest {
    val viewModel = loaded()
    checklist.forcedError = OrderError.Unknown
    viewModel.setPurchased(item(milk), true)
    advanceUntilIdle()
    val failed = content(item(bread), item(milk)).copy(saveError = OrderError.Unknown)
    assertEquals(failed, viewModel.uiState.value)

    checklist.forcedError = null
    viewModel.setPurchased(item(milk), true)
    advanceUntilIdle()
    assertEquals(content(item(bread), item(milk, purchased = true)), viewModel.uiState.value)
  }

  @Test
  fun refreshDuringAPendingSaveWaitsForItAndKeepsTheSavedItem() = runTest {
    val saveCanFinish = CompletableDeferred<Unit>()
    val slowSaves =
        object : ShoppingChecklistRepository by checklist {
          override suspend fun setPurchased(
              tripId: String,
              item: ShoppingChecklistItem,
              purchased: Boolean,
          ): OrderResult<Unit> {
            saveCanFinish.await()
            return checklist.setPurchased(tripId, item, purchased)
          }
        }
    val viewModel = ShoppingChecklistViewModel(orders, slowSaves, trip.id)
    viewModel.refresh()
    advanceUntilIdle()
    viewModel.setPurchased(item(milk), true)
    runCurrent()
    viewModel.refresh()
    runCurrent()
    assertEquals(ShoppingChecklistUiState.Loading, viewModel.uiState.value)

    saveCanFinish.complete(Unit)
    advanceUntilIdle()
    assertEquals(content(item(bread), item(milk, purchased = true)), viewModel.uiState.value)
  }

  @Test
  fun accountChangeClearsTheChecklistAndDiscardsLateSaves() = runTest {
    val viewModel = loaded()
    viewModel.setPurchased(item(milk), true)
    sessionUser = null
    viewModel.refresh()
    advanceUntilIdle()
    val signedOut = ShoppingChecklistUiState.Error(OrderError.NotAuthenticated)
    assertEquals(signedOut, viewModel.uiState.value)
    // Toggles are ignored outside the content state.
    viewModel.setPurchased(item(milk), true)
    assertEquals(signedOut, viewModel.uiState.value)
  }
}
