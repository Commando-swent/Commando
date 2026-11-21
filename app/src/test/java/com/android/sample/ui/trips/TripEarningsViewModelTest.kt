package com.android.sample.ui.trips

// AI assistance: Claude Code.
import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.data.repository.OrderError
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.model.Location
import com.commando.app.model.Order
import com.commando.app.model.OrderItem
import com.commando.app.model.OrderStatus
import com.commando.app.model.Trip
import com.commando.app.model.TripEarnings
import com.commando.app.model.TripStatus
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
class TripEarningsViewModelTest {
  private val now = Instant.parse("2026-11-13T12:00:00Z")
  private val trip =
      Trip(
          id = "trip-1",
          ownerId = "owner",
          store = Location("Migros", 46.52, 6.63),
          scheduledAt = now.plusSeconds(3600),
          handoffLocation = Location("EPFL", 46.52, 6.57),
          status = TripStatus.IN_PROGRESS,
          createdAt = now,
          updatedAt = now,
      )
  private val milk = OrderItem("milk", "Milk", unitPriceCents = 190, quantity = 1)

  private fun order(id: String, status: OrderStatus, commissionCents: Int) =
      Order(id, trip.id, "requester", listOf(milk), commissionCents, status, now)

  private val delivered = order("delivered", OrderStatus.DELIVERED, 250)
  private val ready = order("ready", OrderStatus.READY_FOR_HANDOFF, 400)
  private val cancelled = order("cancelled", OrderStatus.CANCELLED, 900)

  private var sessionUser: String? = "owner"
  private val trips = FakeTripRepository("owner", now = { now }, initialTrips = listOf(trip))
  private val orders =
      FakeOrderRepository(
          { sessionUser },
          listOf(delivered, ready, cancelled),
          tripRepository = trips,
      )

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun content(earnings: TripEarnings) = TripEarningsUiState.Content(earnings)

  @Test
  fun ownerSeesTheCommissionOfDeliveredOrdersOnly() = runTest {
    val viewModel = TripEarningsViewModel(orders, trip.id)
    assertEquals(TripEarningsUiState.Loading, viewModel.uiState.value)
    advanceUntilIdle()
    assertEquals(content(TripEarnings(250L, 1)), viewModel.uiState.value)
  }

  @Test
  fun retryAfterAFailureShowsTheEarnings() = runTest {
    orders.forcedError = OrderError.Unknown
    val viewModel = TripEarningsViewModel(orders, trip.id)
    advanceUntilIdle()
    assertEquals(TripEarningsUiState.Error(OrderError.Unknown), viewModel.uiState.value)
    orders.forcedError = null
    viewModel.refresh()
    advanceUntilIdle()
    assertEquals(content(TripEarnings(250L, 1)), viewModel.uiState.value)
  }

  @Test
  fun repositoryExceptionBecomesARetryableError() = runTest {
    val throwing =
        object : OrderRepository by orders {
          override suspend fun getOrdersForTrip(tripId: String): OrderResult<List<Order>> =
              error("Unexpected failure")
        }
    val viewModel = TripEarningsViewModel(throwing, trip.id)
    advanceUntilIdle()
    assertEquals(TripEarningsUiState.Error(OrderError.Unknown), viewModel.uiState.value)
  }

  @Test
  fun deferredFirstLoadWaitsForRefreshAndALateReplyIsDiscarded() = runTest {
    val gates = mutableListOf<CompletableDeferred<OrderResult<List<Order>>>>()
    // Each query waits on its own gate and ignores cancellation, like a late network reply.
    val gated =
        object : OrderRepository by orders {
          override suspend fun getOrdersForTrip(tripId: String): OrderResult<List<Order>> {
            val gate = CompletableDeferred<OrderResult<List<Order>>>().also { gates += it }
            return withContext(NonCancellable) { gate.await() }
          }
        }
    val viewModel = TripEarningsViewModel(gated, trip.id, loadImmediately = false)
    advanceUntilIdle()
    assertEquals(0, gates.size)
    viewModel.refresh()
    runCurrent()
    viewModel.refresh()
    runCurrent()
    gates[0].complete(OrderResult.Success(listOf(delivered, ready)))
    advanceUntilIdle()
    assertEquals(TripEarningsUiState.Loading, viewModel.uiState.value)
    gates[1].complete(OrderResult.Success(emptyList()))
    advanceUntilIdle()
    assertEquals(content(TripEarnings(0L, 0)), viewModel.uiState.value)
  }
}
