package com.android.sample.ui.orders

// AI assistance: Claude (Anthropic).

import com.android.sample.data.repository.FakeOrderRepository
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.OrderError
import com.android.sample.data.repository.OrderRepository
import com.android.sample.data.repository.OrderResult
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Location
import com.android.sample.model.Order
import com.android.sample.model.OrderDecision
import com.android.sample.model.OrderDraft
import com.android.sample.model.OrderItem
import com.android.sample.model.OrderStatus
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MyOrdersViewModelTest {
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

  private fun order(id: String, requester: String, createdAt: Instant = now) =
      Order(
          id = id,
          tripId = trip.id,
          requesterId = requester,
          items = listOf(apples, milk),
          commissionCents = 200,
          status = OrderStatus.PENDING,
          createdAt = createdAt,
      )

  private val aliceNew = order("alice-new", "alice", now.plusSeconds(60))
  private val aliceOld = order("alice-old", "alice")
  private val bobOrder = order("bob-1", "bob")
  private val allOrders = listOf(aliceOld, bobOrder, aliceNew)

  private var sessionUser: String? = "alice"
  private val orders = FakeOrderRepository({ sessionUser }, allOrders)
  private val trips = FakeTripRepository("owner", now = { now }, initialTrips = listOf(trip))

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun rowIds(state: MyOrdersUiState) =
      (state as MyOrdersUiState.Content).rows.map { it.order.id }

  @Test
  fun startsLoadingThenShowsOnlyRequesterOrdersNewestFirst() = runTest {
    val viewModel = MyOrdersViewModel(orders, trips, "alice")
    assertEquals(MyOrdersUiState.Loading, viewModel.uiState.value)
    advanceUntilIdle()
    assertEquals(listOf("alice-new", "alice-old"), rowIds(viewModel.uiState.value))
  }

  @Test
  fun foreignOrdersReturnedByRepositoryAreExcluded() = runTest {
    // Session is bob, route requester is alice: the repository returns bob's orders only.
    sessionUser = "bob"
    val viewModel = MyOrdersViewModel(orders, trips, "alice")
    advanceUntilIdle()
    assertEquals(MyOrdersUiState.Empty, viewModel.uiState.value)

    val mixed = GatedOrderRepository()
    val mixedViewModel = MyOrdersViewModel(mixed, trips, "alice")
    runCurrent()
    mixed.gates.single().complete(OrderResult.Success(listOf(bobOrder, aliceOld)))
    advanceUntilIdle()
    assertEquals(listOf("alice-old"), rowIds(mixedViewModel.uiState.value))
  }

  @Test
  fun requesterWithoutOrdersIsEmpty() = runTest {
    sessionUser = "carol"
    val viewModel = MyOrdersViewModel(orders, trips, "carol")
    advanceUntilIdle()
    assertEquals(MyOrdersUiState.Empty, viewModel.uiState.value)
  }

  @Test
  fun repositoryErrorBecomesErrorAndRetryRecovers() = runTest {
    orders.forcedError = OrderError.PermissionDenied
    val viewModel = MyOrdersViewModel(orders, trips, "alice")
    advanceUntilIdle()
    assertEquals(MyOrdersUiState.Error(OrderError.PermissionDenied), viewModel.uiState.value)

    orders.forcedError = null
    viewModel.retry()
    assertEquals(MyOrdersUiState.Loading, viewModel.uiState.value)
    advanceUntilIdle()
    assertEquals(listOf("alice-new", "alice-old"), rowIds(viewModel.uiState.value))
  }

  @Test
  fun unexpectedExceptionBecomesUnknownAndRetryRecovers() = runTest {
    val repository = GatedOrderRepository()
    val viewModel = MyOrdersViewModel(repository, trips, "alice")
    runCurrent()
    repository.gates[0].completeExceptionally(IllegalStateException("boom"))
    advanceUntilIdle()
    assertEquals(MyOrdersUiState.Error(OrderError.Unknown), viewModel.uiState.value)

    viewModel.retry()
    runCurrent()
    repository.gates[1].complete(OrderResult.Success(listOf(aliceOld)))
    advanceUntilIdle()
    assertEquals(listOf("alice-old"), rowIds(viewModel.uiState.value))
  }

  @Test
  fun setRequesterClearsRowsAndShowsNewRequesterOrders() = runTest {
    val viewModel = MyOrdersViewModel(orders, trips, "alice")
    advanceUntilIdle()
    assertEquals(listOf("alice-new", "alice-old"), rowIds(viewModel.uiState.value))

    sessionUser = "bob"
    viewModel.setRequester("bob")
    assertEquals(MyOrdersUiState.Loading, viewModel.uiState.value)
    advanceUntilIdle()
    assertEquals(listOf("bob-1"), rowIds(viewModel.uiState.value))
  }

  @Test
  fun setRequesterWithSameIdDoesNotReload() = runTest {
    val repository = GatedOrderRepository()
    val viewModel = MyOrdersViewModel(repository, trips, "alice")
    runCurrent()
    repository.gates.single().complete(OrderResult.Success(listOf(aliceOld)))
    advanceUntilIdle()

    viewModel.setRequester("alice")
    advanceUntilIdle()
    assertEquals(1, repository.gates.size)
    assertEquals(listOf("alice-old"), rowIds(viewModel.uiState.value))
  }

  @Test
  fun lateResponseForPreviousRequesterIsNeverPublished() = runTest {
    val repository = GatedOrderRepository()
    val viewModel = MyOrdersViewModel(repository, trips, "alice")
    runCurrent()
    viewModel.setRequester("bob")
    runCurrent()
    assertEquals(2, repository.gates.size)

    // Old call ignores cancellation and returns after the switch.
    repository.gates[0].complete(OrderResult.Success(listOf(aliceOld)))
    advanceUntilIdle()
    assertEquals(MyOrdersUiState.Loading, viewModel.uiState.value)

    repository.gates[1].complete(OrderResult.Success(listOf(bobOrder)))
    advanceUntilIdle()
    assertEquals(listOf("bob-1"), rowIds(viewModel.uiState.value))
  }

  @Test
  fun lateResponseAfterNewerLoadCompletedIsDiscarded() = runTest {
    val repository = GatedOrderRepository()
    val viewModel = MyOrdersViewModel(repository, trips, "alice")
    runCurrent()
    viewModel.retry()
    runCurrent()
    repository.gates[1].complete(OrderResult.Success(listOf(aliceNew)))
    advanceUntilIdle()
    repository.gates[0].complete(OrderResult.Error(OrderError.Unknown))
    advanceUntilIdle()
    assertEquals(listOf("alice-new"), rowIds(viewModel.uiState.value))
  }

  @Test
  fun rowsCarryStoredOrderTripAndItemCount() = runTest {
    val viewModel = MyOrdersViewModel(orders, trips, "alice")
    advanceUntilIdle()
    val row = (viewModel.uiState.value as MyOrdersUiState.Content).rows.first()
    assertEquals(aliceNew, row.order)
    assertEquals(listOf(apples, milk), row.order.items)
    assertEquals(200, row.order.commissionCents)
    assertEquals(OrderStatus.PENDING, row.order.status)
    assertEquals(trip, row.trip)
    assertEquals(3L, row.itemCount)
  }

  @Test
  fun tripErrorKeepsOrderWithNullTrip() = runTest {
    trips.forcedError = TripError.NetworkError
    val viewModel = MyOrdersViewModel(orders, trips, "alice")
    advanceUntilIdle()
    val rows = (viewModel.uiState.value as MyOrdersUiState.Content).rows
    assertEquals(listOf(aliceNew, aliceOld), rows.map { it.order })
    rows.forEach { assertNull(it.trip) }
  }

  @Test
  fun tripExceptionKeepsOrderWithNullTrip() = runTest {
    val throwingTrips =
        object : TripRepository by trips {
          override suspend fun getTripById(tripId: String): TripResult<Trip> =
              throw IllegalStateException("boom")
        }
    val viewModel = MyOrdersViewModel(orders, throwingTrips, "alice")
    advanceUntilIdle()
    val rows = (viewModel.uiState.value as MyOrdersUiState.Content).rows
    assertEquals(listOf("alice-new", "alice-old"), rows.map { it.order.id })
    rows.forEach { assertNull(it.trip) }
  }

  @Test
  fun strayOrderCancellationBecomesUnknownAndRetryRecovers() = runTest {
    val repository = GatedOrderRepository()
    val viewModel = MyOrdersViewModel(repository, trips, "alice")
    runCurrent()
    // Thrown by the repository (e.g. an internal timeout) while the load job is still active.
    repository.gates[0].completeExceptionally(CancellationException("timeout"))
    advanceUntilIdle()
    assertEquals(MyOrdersUiState.Error(OrderError.Unknown), viewModel.uiState.value)

    viewModel.retry()
    runCurrent()
    repository.gates[1].complete(OrderResult.Success(listOf(aliceNew, aliceOld)))
    advanceUntilIdle()
    assertEquals(listOf("alice-new", "alice-old"), rowIds(viewModel.uiState.value))
  }

  @Test
  fun strayTripCancellationKeepsOrderWithNullTrip() = runTest {
    val cancellingTrips =
        object : TripRepository by trips {
          override suspend fun getTripById(tripId: String): TripResult<Trip> =
              throw CancellationException("timeout")
        }
    val viewModel = MyOrdersViewModel(orders, cancellingTrips, "alice")
    advanceUntilIdle()
    val rows = (viewModel.uiState.value as MyOrdersUiState.Content).rows
    assertEquals(listOf(aliceNew, aliceOld), rows.map { it.order })
    rows.forEach { assertNull(it.trip) }
  }

  @Test
  fun unknownTripKeepsOrderWithNullTrip() = runTest {
    val viewModel = MyOrdersViewModel(orders, FakeTripRepository("owner", now = { now }), "alice")
    advanceUntilIdle()
    val rows = (viewModel.uiState.value as MyOrdersUiState.Content).rows
    assertEquals(listOf("alice-new", "alice-old"), rows.map { it.order.id })
    rows.forEach { assertNull(it.trip) }
  }

  @Test
  fun swallowedStaleResultAfterNewRequesterLoadedIsDiscarded() = runTest {
    val repository = GatedOrderRepository(swallowCancellation = true)
    val viewModel = MyOrdersViewModel(repository, trips, "alice")
    runCurrent()
    viewModel.setRequester("bob")
    runCurrent()
    assertEquals(2, repository.gates.size)

    repository.gates[1].complete(OrderResult.Success(listOf(bobOrder)))
    advanceUntilIdle()
    assertEquals(listOf("bob-1"), rowIds(viewModel.uiState.value))

    // Alice's cancelled call swallows cancellation and still returns her orders.
    repository.gates[0].complete(OrderResult.Success(listOf(aliceOld)))
    advanceUntilIdle()
    assertEquals(1, repository.staleReturns)
    assertEquals(listOf("bob-1"), rowIds(viewModel.uiState.value))
  }

  @Test
  fun swallowedStaleResultWhileNewRequesterPendingKeepsLoading() = runTest {
    val repository = GatedOrderRepository(swallowCancellation = true)
    val viewModel = MyOrdersViewModel(repository, trips, "alice")
    runCurrent()
    viewModel.setRequester("bob")
    runCurrent()

    repository.gates[0].complete(OrderResult.Success(listOf(aliceOld)))
    advanceUntilIdle()
    assertEquals(1, repository.staleReturns)
    assertEquals(MyOrdersUiState.Loading, viewModel.uiState.value)

    repository.gates[1].complete(OrderResult.Success(listOf(bobOrder)))
    advanceUntilIdle()
    assertEquals(listOf("bob-1"), rowIds(viewModel.uiState.value))
  }

  /**
   * Each getMyOrders call waits on its own gate, ignoring cancellation like a late network call.
   * With [swallowCancellation], a cancelled call catches the cancellation (like a repository that
   * maps every exception to a result) and still returns its gate's value to the cancelled caller.
   */
  private class GatedOrderRepository(private val swallowCancellation: Boolean = false) :
      OrderRepository {
    val gates = mutableListOf<CompletableDeferred<OrderResult<List<Order>>>>()
    var staleReturns = 0

    override suspend fun getMyOrders(): OrderResult<List<Order>> {
      val gate = CompletableDeferred<OrderResult<List<Order>>>()
      gates += gate
      if (!swallowCancellation) return withContext(NonCancellable) { gate.await() }
      return try {
        gate.await()
      } catch (_: CancellationException) {
        // suspendCoroutine does not check cancellation, so the stale value reaches the caller.
        val stale = suspendCoroutine { cont ->
          gate.invokeOnCompletion { cont.resume(gate.getCompleted()) }
        }
        staleReturns++
        stale
      }
    }

    override suspend fun createOrder(draft: OrderDraft): OrderResult<Order> = error("Not used")

    override suspend fun decideOrder(orderId: String, decision: OrderDecision): OrderResult<Order> =
        error("Not used")

    override suspend fun getOrderById(orderId: String): OrderResult<Order> = error("Not used")

    override suspend fun getOrdersForTrip(tripId: String): OrderResult<List<Order>> =
        error("Not used")
  }
}
