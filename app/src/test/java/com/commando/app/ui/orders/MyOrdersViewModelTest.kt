package com.commando.app.ui.orders

// AI assistance: OpenAI Codex (requester cancellation).
// AI assistance: Claude (Anthropic), including the #401 test double.

import com.commando.app.data.cache.SessionCache
import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.data.repository.OrderError
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.TripError
import com.commando.app.data.repository.TripRepository
import com.commando.app.data.repository.TripResult
import com.commando.app.model.Location
import com.commando.app.model.Order
import com.commando.app.model.OrderDecision
import com.commando.app.model.OrderDraft
import com.commando.app.model.OrderItem
import com.commando.app.model.OrderStatus
import com.commando.app.model.Trip
import com.commando.app.model.TripStatus
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertTrue
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
  fun closingTheSessionDuringAnOrderReadDropsTheResult() = runTest {
    val cache = com.commando.app.data.cache.SessionCache { sessionUser }
    val delayed = GatedOrderRepository()
    val vm = MyOrdersViewModel(delayed, trips, "alice", cache = cache)
    runCurrent()
    cache.close()
    delayed.gates.single().complete(OrderResult.Success(listOf(aliceOld)))
    advanceUntilIdle()
    assertEquals(MyOrdersUiState.Loading, vm.uiState.value)
    assertNull(cache.get(historyKey("alice")))
  }

  @Test
  fun coldOfflineHistoryHasNoSavedCopy() = runTest {
    val cache = SessionCache { sessionUser }
    val model =
        MyOrdersViewModel(orders, trips, "alice", cache = cache, online = MutableStateFlow(false))

    advanceUntilIdle()

    assertEquals(MyOrdersUiState.OfflineUnavailable, model.uiState.value)
    assertNull(cache.get(historyKey("alice")))
  }

  @Test
  fun temporaryReadFailureKeepsSavedRowsAndTheirConfirmationTime() = runTest {
    val cache = cacheWithConfirmedHistory()
    orders.forcedError = OrderError.NetworkError
    val model = MyOrdersViewModel(orders, trips, "alice", cache = cache)

    advanceUntilIdle()

    val saved = requireNotNull(cache.get(historyKey("alice")))
    assertEquals(now, saved.confirmedAt)
    assertEquals(
        MyOrdersUiState.Content(listOf(MyOrderRow(aliceOld, trip)), now),
        model.uiState.value,
    )
  }

  @Test
  fun permissionDenialEvictsSavedRowsAndClearsThemFromTheUi() = runTest {
    val cache = cacheWithConfirmedHistory()
    orders.forcedError = OrderError.PermissionDenied
    val online = MutableStateFlow(true)
    val model = MyOrdersViewModel(orders, trips, "alice", cache = cache, online = online)

    advanceUntilIdle()

    assertEquals(MyOrdersUiState.Error(OrderError.PermissionDenied), model.uiState.value)
    assertNull(cache.get(historyKey("alice")))
    online.value = false
    advanceUntilIdle()
    assertEquals(MyOrdersUiState.OfflineUnavailable, model.uiState.value)
  }

  private fun cacheWithConfirmedHistory() = SessionCache {
    sessionUser
  }
      .apply { put(historyKey("alice"), listOf(MyOrderRow(aliceOld, trip)), confirmedAt = now) }

  @Test
  fun savedRowsStayVisibleDuringReconnectionUntilTheServerConfirmsNewRows() = runTest {
    val cache = SessionCache { sessionUser }
    val savedRows = listOf(MyOrderRow(aliceOld, trip))
    cache.put(historyKey("alice"), savedRows, confirmedAt = now)
    val online = MutableStateFlow(false)
    val delayed = GatedOrderRepository()
    val vm = MyOrdersViewModel(delayed, trips, "alice", cache = cache, online = online)
    val savedState = MyOrdersUiState.Content(savedRows, now)
    assertEquals(savedState, vm.uiState.value)

    online.value = true
    runCurrent()
    assertEquals(savedState, vm.uiState.value)
    assertEquals(now, cache.get(historyKey("alice"))?.confirmedAt)

    val updated = aliceOld.copy(status = OrderStatus.CANCELLED)
    delayed.gates.single().complete(OrderResult.Success(listOf(updated)))
    advanceUntilIdle()
    val updatedRows = listOf(MyOrderRow(updated, trip))
    assertEquals(MyOrdersUiState.Content(updatedRows), vm.uiState.value)
    assertEquals(updatedRows, cache.get(historyKey("alice"))?.value)
    assertTrue(requireNotNull(cache.get(historyKey("alice"))).confirmedAt > now)
  }

  @Test
  fun secondDisconnectKeepsPreviouslyConfirmedEmptyHistoryAndIgnoresLateRefresh() = runTest {
    val cache = SessionCache { sessionUser }
    cache.put(historyKey("alice"), emptyList(), confirmedAt = now)
    val online = MutableStateFlow(false)
    val delayed = GatedOrderRepository(swallowCancellation = true)
    val vm = MyOrdersViewModel(delayed, trips, "alice", cache = cache, online = online)
    val confirmedEmpty = MyOrdersUiState.Content(emptyList(), now)

    online.value = true
    runCurrent()
    assertEquals(confirmedEmpty, vm.uiState.value)
    online.value = false
    runCurrent()
    delayed.gates.single().complete(OrderResult.Success(listOf(aliceOld)))
    advanceUntilIdle()

    assertEquals(1, delayed.staleReturns)
    assertEquals(confirmedEmpty, vm.uiState.value)
    assertEquals(emptyList<MyOrderRow>(), cache.get(historyKey("alice"))?.value)
    assertEquals(now, cache.get(historyKey("alice"))?.confirmedAt)
  }

  @Test
  fun refreshAfterCancellationRetainsHistoryWithPersistedStatusAndUnchangedOtherRows() = runTest {
    val vm = MyOrdersViewModel(orders, trips, "alice")
    advanceUntilIdle()
    val cancelled = aliceNew.copy(status = OrderStatus.CANCELLED)
    assertEquals(OrderResult.Success(cancelled), orders.cancelOrder(aliceNew.id))
    vm.retry()
    advanceUntilIdle()
    val rows = (vm.uiState.value as MyOrdersUiState.Content).rows
    assertEquals(listOf(cancelled, aliceOld), rows.map { it.order })
    assertEquals(trip, rows.first().trip)
    val reopened = MyOrdersViewModel(orders, trips, "alice")
    advanceUntilIdle()
    assertEquals(rows, (reopened.uiState.value as MyOrdersUiState.Content).rows)
  }

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
    override suspend fun editOrder(
        orderId: String,
        draft: OrderDraft,
        expectedRevision: Int,
    ): OrderResult<Order> = error("Not used")

    override suspend fun confirmReceipt(orderId: String): OrderResult<Order> = error("Not used")

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

    override suspend fun markReadyForHandoff(orderId: String): OrderResult<Order> =
        error("Not used")

    override suspend fun cancelAcceptedOrder(orderId: String): OrderResult<Order> =
        error("Not used")

    override suspend fun decideOrder(
        orderId: String,
        decision: OrderDecision,
        expectedRevision: Int,
    ): OrderResult<Order> = error("Not used")

    override suspend fun cancelOrder(orderId: String): OrderResult<Order> = error("Not used")

    override suspend fun getOrderById(orderId: String): OrderResult<Order> = error("Not used")

    override suspend fun getOrdersForTrip(tripId: String): OrderResult<List<Order>> =
        error("Not used")
  }
}
