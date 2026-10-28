package com.android.sample.ui.orders

// AI assistance: Claude Code.
import com.android.sample.data.repository.FakeOrderRepository
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.OrderError
import com.android.sample.data.repository.OrderRepository
import com.android.sample.data.repository.OrderResult
import com.android.sample.model.Location
import com.android.sample.model.Order
import com.android.sample.model.OrderItem
import com.android.sample.model.OrderStatus
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingOrdersViewModelTest {
  private val now = Instant.parse("2026-10-06T12:00:00Z")
  private val trip =
      Trip(
          id = "trip-1",
          ownerId = "owner",
          store = Location("Migros", 46.52, 6.63),
          scheduledAt = now.plusSeconds(3600),
          handoffLocation = Location("EPFL", 46.52, 6.57),
          status = TripStatus.PUBLISHED,
          createdAt = now,
          updatedAt = now,
      )
  private val apples = OrderItem("apple", "Apples", unitPriceCents = 150, quantity = 2)
  private val milk = OrderItem("milk", "Milk", unitPriceCents = 190, quantity = 1)

  private fun order(id: String, tripId: String = trip.id, createdAt: Instant = now) =
      Order(id, tripId, "requester", listOf(apples, milk), 200, OrderStatus.PENDING, createdAt)

  private val newer = order("newer", createdAt = now.plusSeconds(60))
  private val older = order("older")
  private val elsewhere = order("elsewhere", tripId = "trip-2")

  private var sessionUser: String? = "owner"
  private val trips = FakeTripRepository("owner", now = { now }, initialTrips = listOf(trip))
  private val orders =
      FakeOrderRepository({ sessionUser }, listOf(older, elsewhere, newer), tripRepository = trips)

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun rowIds(state: IncomingOrdersUiState) =
      (state as IncomingOrdersUiState.Content).rows.map { it.order.id }

  @Test
  fun ownerSeesOnlyThisTripsOrdersNewestFirstWithTheirSummary() = runTest {
    val viewModel = IncomingOrdersViewModel(orders, trip.id)
    assertEquals(IncomingOrdersUiState.Loading, viewModel.uiState.value)
    advanceUntilIdle()
    assertEquals(listOf("newer", "older"), rowIds(viewModel.uiState.value))
    val row = (viewModel.uiState.value as IncomingOrdersUiState.Content).rows.first()
    assertEquals(3L, row.itemCount)
    // 2 x 1.50 + 1 x 1.90 + 2.00 commission.
    assertEquals(690L, row.totalCents)
  }

  @Test
  fun tripWithoutOrdersIsEmpty() = runTest {
    val viewModel =
        IncomingOrdersViewModel(
            FakeOrderRepository({ sessionUser }, tripRepository = trips),
            trip.id,
        )
    advanceUntilIdle()
    assertEquals(IncomingOrdersUiState.Empty, viewModel.uiState.value)
  }

  @Test
  fun missingTripBlankIdAndNonOwnersShowErrors() = runTest {
    val missing = IncomingOrdersViewModel(orders, "missing-trip")
    val blank = IncomingOrdersViewModel(orders, " ")
    advanceUntilIdle()
    assertEquals(IncomingOrdersUiState.Error(OrderError.PermissionDenied), missing.uiState.value)
    assertEquals(IncomingOrdersUiState.Error(OrderError.InvalidData), blank.uiState.value)
    sessionUser = "requester"
    val requester = IncomingOrdersViewModel(orders, trip.id)
    advanceUntilIdle()
    assertEquals(IncomingOrdersUiState.Error(OrderError.PermissionDenied), requester.uiState.value)
  }

  @Test
  fun retryAfterAFailureShowsTheOrders() = runTest {
    orders.forcedError = OrderError.Unknown
    val viewModel = IncomingOrdersViewModel(orders, trip.id)
    advanceUntilIdle()
    assertEquals(IncomingOrdersUiState.Error(OrderError.Unknown), viewModel.uiState.value)
    orders.forcedError = null
    viewModel.refresh()
    assertEquals(IncomingOrdersUiState.Loading, viewModel.uiState.value)
    advanceUntilIdle()
    assertEquals(listOf("newer", "older"), rowIds(viewModel.uiState.value))
  }

  @Test
  fun accountChangeClearsRowsAndTheNewSessionCannotReadThem() = runTest {
    val viewModel = IncomingOrdersViewModel(orders, trip.id)
    advanceUntilIdle()
    assertEquals(listOf("newer", "older"), rowIds(viewModel.uiState.value))
    sessionUser = "someone-else"
    viewModel.refresh()
    assertEquals(IncomingOrdersUiState.Loading, viewModel.uiState.value)
    advanceUntilIdle()
    assertEquals(IncomingOrdersUiState.Error(OrderError.PermissionDenied), viewModel.uiState.value)
    sessionUser = null
    viewModel.refresh()
    advanceUntilIdle()
    assertEquals(IncomingOrdersUiState.Error(OrderError.NotAuthenticated), viewModel.uiState.value)
  }

  @Test
  fun lateReplyOfASupersededLoadIsDiscarded() = runTest {
    val repository = GatedOrderRepository()
    val viewModel = IncomingOrdersViewModel(repository, trip.id)
    runCurrent()
    viewModel.refresh()
    runCurrent()
    repository.gates[0].complete(OrderResult.Success(listOf(older)))
    advanceUntilIdle()
    assertEquals(IncomingOrdersUiState.Loading, viewModel.uiState.value)
    repository.gates[1].complete(OrderResult.Success(listOf(newer)))
    advanceUntilIdle()
    assertEquals(listOf("newer"), rowIds(viewModel.uiState.value))
    assertEquals(listOf(trip.id, trip.id), repository.tripIds)
  }

  @Test
  fun repositoryExceptionBecomesARetryableError() = runTest {
    val throwing =
        object : OrderRepository by orders {
          override suspend fun getOrdersForTrip(tripId: String): OrderResult<List<Order>> =
              error("Unexpected failure")
        }
    val viewModel = IncomingOrdersViewModel(throwing, trip.id)
    advanceUntilIdle()
    assertEquals(IncomingOrdersUiState.Error(OrderError.Unknown), viewModel.uiState.value)
  }

  @Test
  fun deferredFirstLoadWaitsForRefresh() = runTest {
    val viewModel = IncomingOrdersViewModel(orders, trip.id, loadImmediately = false)
    advanceUntilIdle()
    assertEquals(IncomingOrdersUiState.Loading, viewModel.uiState.value)
    viewModel.refresh()
    advanceUntilIdle()
    assertEquals(listOf("newer", "older"), rowIds(viewModel.uiState.value))
  }

  /** Each trip query waits on its own gate and ignores cancellation, like a late network reply. */
  private inner class GatedOrderRepository : OrderRepository by orders {
    val gates = mutableListOf<CompletableDeferred<OrderResult<List<Order>>>>()
    val tripIds = mutableListOf<String>()

    override suspend fun getOrdersForTrip(tripId: String): OrderResult<List<Order>> {
      tripIds += tripId
      val gate = CompletableDeferred<OrderResult<List<Order>>>().also { gates += it }
      return withContext(NonCancellable) { gate.await() }
    }
  }
}
