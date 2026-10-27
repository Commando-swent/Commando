package com.android.sample.ui.orders

// AI assistance: OpenAI Codex.

import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.OrderError
import com.android.sample.data.repository.OrderRepository
import com.android.sample.data.repository.OrderResult
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Location
import com.android.sample.model.NewTrip
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrderDetailsViewModelTest {
  private val now = Instant.parse("2026-10-13T12:00:00Z")
  private val trip =
      Trip(
          id = " trip/opaque ?#%+ ID ",
          ownerId = "commando",
          store = Location("Migros", 46.52, 6.63),
          scheduledAt = now.minusSeconds(60),
          handoffLocation = Location("EPFL", 46.51, 6.57),
          status = TripStatus.COMPLETED,
          createdAt = now.minusSeconds(3600),
          updatedAt = now,
      )
  private val order =
      Order(
          id = " order/opaque ?#%+ ID ",
          tripId = trip.id,
          requesterId = "requester",
          items =
              listOf(
                  OrderItem("apple", "Stored apples", 151, 2),
                  OrderItem("milk", "Milk", 190, 1),
              ),
          commissionCents = 203,
          status = OrderStatus.PENDING,
          createdAt = now.minusSeconds(120),
      )
  private val orders = RecordingOrders()
  private val trips = RecordingTrips()

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() = OrderDetailsViewModel(order.id, orders, trips)

  @Test
  fun automaticallyLoadsExactIdsInSequenceAndPreservesBothOriginalObjects() = runTest {
    val orderGate = CompletableDeferred<OrderResult<Order>>()
    val tripGate = CompletableDeferred<TripResult<Trip>>()
    orders.lookup = { orderGate.await() }
    trips.lookup = { tripGate.await() }
    val vm = viewModel()
    val states = mutableListOf<OrderDetailsUiState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      vm.uiState.collect { states += it }
    }
    assertEquals(OrderDetailsUiState.Loading, vm.uiState.value)
    assertTrue(orders.ids.isEmpty())
    runCurrent()
    assertEquals(listOf(order.id), orders.ids)
    assertTrue(trips.ids.isEmpty())
    assertEquals(OrderDetailsUiState.Loading, vm.uiState.value)
    orderGate.complete(OrderResult.Success(order))
    runCurrent()
    assertEquals(listOf(order.tripId), trips.ids)
    assertEquals(listOf(OrderDetailsUiState.Loading), states)
    tripGate.complete(TripResult.Success(trip))
    advanceUntilIdle()
    val content = vm.uiState.value as OrderDetailsUiState.Content
    assertSame(order, content.order)
    assertSame(trip, content.trip)
    assertEquals(
        listOf(OrderDetailsUiState.Loading, OrderDetailsUiState.Content(order, trip)),
        states,
    )
  }

  @Test
  fun everyOrderErrorPreventsTripLookupAndCanRecoverOnRetry() = runTest {
    for (error in
        listOf(
            OrderError.NotFound,
            OrderError.NotAuthenticated,
            OrderError.PermissionDenied,
            OrderError.InvalidData,
            OrderError.Unknown,
        )) {
      orders.ids.clear()
      trips.ids.clear()
      orders.lookup = { OrderResult.Error(error) }
      val vm = viewModel()
      advanceUntilIdle()
      val expected =
          if (error == OrderError.NotFound) OrderDetailsUiState.NotFound
          else OrderDetailsUiState.Error.OrderFailure(error)
      assertEquals(expected, vm.uiState.value)
      assertTrue(trips.ids.isEmpty())
      orders.lookup = { OrderResult.Success(order) }
      vm.retry()
      assertEquals(OrderDetailsUiState.Loading, vm.uiState.value)
      advanceUntilIdle()
      assertEquals(listOf(order.id, order.id), orders.ids)
      assertEquals(listOf(trip.id), trips.ids)
      assertEquals(OrderDetailsUiState.Content(order, trip), vm.uiState.value)
    }
  }

  @Test
  fun everyTripErrorRetainsItsTypeWithoutPublishingIncompleteContentAndRetryRestartsOrder() =
      runTest {
        for (error in
            listOf(
                TripError.NotFound,
                TripError.PermissionDenied,
                TripError.NetworkError,
                TripError.InvalidData,
                TripError.Unknown,
            )) {
          orders.ids.clear()
          trips.ids.clear()
          trips.lookup = { TripResult.Error(error) }
          val vm = viewModel()
          val states = mutableListOf<OrderDetailsUiState>()
          val collector =
              backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.uiState.collect { states += it }
              }
          advanceUntilIdle()
          assertEquals(
              listOf(OrderDetailsUiState.Loading, OrderDetailsUiState.Error.TripFailure(error)),
              states,
          )
          trips.lookup = { TripResult.Success(trip) }
          vm.retry()
          advanceUntilIdle()
          assertEquals(listOf(order.id, order.id), orders.ids)
          assertEquals(listOf(trip.id, trip.id), trips.ids)
          assertEquals(OrderDetailsUiState.Content(order, trip), vm.uiState.value)
          collector.cancel()
        }
      }

  @Test
  fun orderExceptionBecomesUnknownAndReleasesGuardForRetry() = runTest {
    orders.lookup = { throw IllegalStateException("order failure") }
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(OrderDetailsUiState.Error.OrderFailure(OrderError.Unknown), vm.uiState.value)
    assertTrue(trips.ids.isEmpty())
    orders.lookup = { OrderResult.Success(order) }
    vm.retry()
    advanceUntilIdle()
    assertEquals(listOf(order.id, order.id), orders.ids)
    assertEquals(OrderDetailsUiState.Content(order, trip), vm.uiState.value)
  }

  @Test
  fun tripExceptionBecomesUnknownAndReleasesGuardForRetry() = runTest {
    trips.lookup = { throw IllegalArgumentException("trip failure") }
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(OrderDetailsUiState.Error.TripFailure(TripError.Unknown), vm.uiState.value)
    trips.lookup = { TripResult.Success(trip) }
    vm.retry()
    advanceUntilIdle()
    assertEquals(listOf(order.id, order.id), orders.ids)
    assertEquals(listOf(trip.id, trip.id), trips.ids)
    assertEquals(OrderDetailsUiState.Content(order, trip), vm.uiState.value)
  }

  @Test
  fun rapidRetriesDuringEitherLookupAreIgnoredAndCompletedLoadCanRefresh() = runTest {
    val orderGate = CompletableDeferred<OrderResult<Order>>()
    val tripGate = CompletableDeferred<TripResult<Trip>>()
    orders.lookup = { orderGate.await() }
    trips.lookup = { tripGate.await() }
    val vm = viewModel()
    repeat(5) { vm.retry() }
    runCurrent()
    repeat(5) { vm.retry() }
    runCurrent()
    assertEquals(listOf(order.id), orders.ids)
    orderGate.complete(OrderResult.Success(order))
    runCurrent()
    repeat(5) { vm.retry() }
    runCurrent()
    assertEquals(listOf(order.id), orders.ids)
    assertEquals(listOf(trip.id), trips.ids)
    tripGate.complete(TripResult.Success(trip))
    advanceUntilIdle()
    assertEquals(OrderDetailsUiState.Content(order, trip), vm.uiState.value)
    val refreshed = trip.copy(status = TripStatus.CANCELLED, updatedAt = now.plusSeconds(10))
    trips.lookup = { TripResult.Success(refreshed) }
    vm.retry()
    assertEquals(OrderDetailsUiState.Loading, vm.uiState.value)
    repeat(5) { vm.retry() }
    advanceUntilIdle()
    assertEquals(listOf(order.id, order.id), orders.ids)
    assertEquals(listOf(trip.id, trip.id), trips.ids)
    assertEquals(OrderDetailsUiState.Content(order, refreshed), vm.uiState.value)
  }

  @Test
  fun repositoryThrownCancellationAtEitherStageBecomesUnknownAndAllowsRetry() = runTest {
    // An independently thrown exception is distinct from actual cancellation of the calling job.
    for (atOrder in listOf(true, false)) {
      orders.ids.clear()
      trips.ids.clear()
      orders.lookup = {
        assertTrue(currentCoroutineContext().isActive)
        if (atOrder) throw CancellationException("internal cancellation")
        else OrderResult.Success(order)
      }
      trips.lookup = {
        assertTrue(currentCoroutineContext().isActive)
        throw CancellationException("internal cancellation")
      }
      val vm = viewModel()
      advanceUntilIdle()
      val expected =
          if (atOrder) OrderDetailsUiState.Error.OrderFailure(OrderError.Unknown)
          else OrderDetailsUiState.Error.TripFailure(TripError.Unknown)
      assertEquals(expected, vm.uiState.value)
      assertEquals(if (atOrder) emptyList<String>() else listOf(trip.id), trips.ids)
      orders.lookup = { OrderResult.Success(order) }
      trips.lookup = { TripResult.Success(trip) }
      vm.retry()
      assertEquals(OrderDetailsUiState.Loading, vm.uiState.value)
      advanceUntilIdle()
      assertEquals(listOf(order.id, order.id), orders.ids)
      assertEquals(if (atOrder) listOf(trip.id) else listOf(trip.id, trip.id), trips.ids)
      assertEquals(OrderDetailsUiState.Content(order, trip), vm.uiState.value)
    }
  }

  @Test
  fun actualScopeCancellationWhileOrderPendingPreventsTripAndContent() = runTest {
    val gate = CompletableDeferred<OrderResult<Order>>()
    orders.lookup = { gate.await() }
    val vm = viewModel()
    runCurrent()
    vm.viewModelScope.cancel()
    gate.complete(OrderResult.Success(order))
    advanceUntilIdle()
    assertEquals(listOf(order.id), orders.ids)
    assertTrue(trips.ids.isEmpty())
    assertEquals(OrderDetailsUiState.Loading, vm.uiState.value)
  }

  @Test
  fun actualScopeCancellationWhileTripPendingPreventsContentAndVisibleError() = runTest {
    val gate = CompletableDeferred<TripResult<Trip>>()
    trips.lookup = { gate.await() }
    val vm = viewModel()
    runCurrent()
    assertEquals(listOf(trip.id), trips.ids)
    vm.viewModelScope.cancel()
    gate.complete(TripResult.Success(trip))
    advanceUntilIdle()
    assertEquals(OrderDetailsUiState.Loading, vm.uiState.value)
  }

  @Test
  fun lateOrderResultReturnedToCancelledCallerCannotTriggerTripOrPublishState() = runTest {
    val gate = LateResult<OrderResult<Order>>()
    orders.lookup = { gate.await() }
    val vm = viewModel()
    runCurrent()
    vm.viewModelScope.cancel()
    gate.complete(OrderResult.Success(order))
    advanceUntilIdle()
    assertEquals(1, gate.returns)
    assertTrue(trips.ids.isEmpty())
    assertEquals(OrderDetailsUiState.Loading, vm.uiState.value)
  }

  @Test
  fun lateTripResultReturnedToCancelledCallerCannotPublishContentOrError() = runTest {
    for (result in listOf(TripResult.Success(trip), TripResult.Error(TripError.Unknown))) {
      val gate = LateResult<TripResult<Trip>>()
      trips.lookup = { gate.await() }
      val vm = viewModel()
      runCurrent()
      vm.viewModelScope.cancel()
      gate.complete(result)
      advanceUntilIdle()
      assertEquals(1, gate.returns)
      assertEquals(OrderDetailsUiState.Loading, vm.uiState.value)
    }
  }

  @Test
  fun lateOrderExceptionAfterActualCancellationPublishesNoStateAndNeverRequestsTrip() = runTest {
    val gate = LateResult<Unit>()
    orders.lookup = {
      gate.await()
      assertTrue(!currentCoroutineContext().isActive)
      throw IllegalStateException("late order failure")
    }
    val vm = viewModel()
    val states = mutableListOf<OrderDetailsUiState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      vm.uiState.collect { states += it }
    }
    runCurrent()
    assertEquals(listOf(order.id), orders.ids)
    assertEquals(0, gate.returns)
    vm.viewModelScope.cancel()
    runCurrent()
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals(1, gate.returns)
    assertTrue(trips.ids.isEmpty())
    assertEquals(listOf(OrderDetailsUiState.Loading), states)
    assertEquals(OrderDetailsUiState.Loading, vm.uiState.value)
  }

  @Test
  fun lateTripExceptionAfterActualCancellationPublishesNoContentOrUnknown() = runTest {
    val gate = LateResult<Unit>()
    trips.lookup = {
      gate.await()
      assertTrue(!currentCoroutineContext().isActive)
      throw IllegalArgumentException("late trip failure")
    }
    val vm = viewModel()
    val states = mutableListOf<OrderDetailsUiState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      vm.uiState.collect { states += it }
    }
    runCurrent()
    assertEquals(listOf(order.id), orders.ids)
    assertEquals(listOf(trip.id), trips.ids)
    assertEquals(0, gate.returns)
    vm.viewModelScope.cancel()
    runCurrent()
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals(1, gate.returns)
    assertEquals(listOf(OrderDetailsUiState.Loading), states)
    assertEquals(OrderDetailsUiState.Loading, vm.uiState.value)
  }

  @Test
  fun authorizedRepositoryResultsNeedNoRequesterRoleOrPresentationArgument() = runTest {
    // These are authorized repository responses, not tests of the fake's access policy.
    // In particular this does not claim the shared fake supports Commando authorization yet.
    for (authorizedOrder in listOf(order, order.copy(requesterId = "another-requester"))) {
      orders.lookup = { OrderResult.Success(authorizedOrder) }
      val vm = OrderDetailsViewModel(order.id, orders, trips)
      advanceUntilIdle()
      assertEquals(OrderDetailsUiState.Content(authorizedOrder, trip), vm.uiState.value)
    }
  }

  @Test
  fun authorizationFailureAfterContentClearsProtectedDataAndDoesNotReloadTrip() = runTest {
    for (error in listOf(OrderError.PermissionDenied, OrderError.NotAuthenticated)) {
      orders.lookup = { OrderResult.Success(order) }
      val vm = viewModel()
      advanceUntilIdle()
      assertEquals(OrderDetailsUiState.Content(order, trip), vm.uiState.value)
      val tripCalls = trips.ids.size
      orders.lookup = { OrderResult.Error(error) }
      vm.retry()
      assertEquals(OrderDetailsUiState.Loading, vm.uiState.value)
      advanceUntilIdle()
      assertEquals(OrderDetailsUiState.Error.OrderFailure(error), vm.uiState.value)
      assertEquals(tripCalls, trips.ids.size)
    }
  }

  /** suspendCoroutine deliberately delivers a result even after the calling job is cancelled. */
  private class LateResult<T> {
    private val gate = CompletableDeferred<T>()
    var returns = 0
      private set

    suspend fun await(): T {
      val result =
          suspendCoroutine<T> { continuation ->
            gate.invokeOnCompletion { continuation.resume(gate.getCompleted()) }
          }
      returns++
      return result
    }

    fun complete(value: T) {
      gate.complete(value)
    }
  }

  private inner class RecordingOrders : OrderRepository {
    val ids = mutableListOf<String>()
    var lookup: suspend () -> OrderResult<Order> = { OrderResult.Success(order) }

    override suspend fun getOrderById(orderId: String): OrderResult<Order> {
      ids += orderId
      return lookup()
    }

    override suspend fun createOrder(draft: OrderDraft): OrderResult<Order> = error("Not used")

    override suspend fun decideOrder(orderId: String, decision: OrderDecision): OrderResult<Order> =
        error("Not used")

    override suspend fun getMyOrders(): OrderResult<List<Order>> = error("Not used")
  }

  private inner class RecordingTrips : TripRepository {
    val ids = mutableListOf<String>()
    var lookup: suspend () -> TripResult<Trip> = { TripResult.Success(trip) }

    override suspend fun getTripById(tripId: String): TripResult<Trip> {
      ids += tripId
      return lookup()
    }

    override suspend fun publishTrip(newTrip: NewTrip): TripResult<Trip> = error("Not used")

    override suspend fun getUpcomingTrips(): TripResult<List<Trip>> = error("Not used")

    override suspend fun getMyTrips(): TripResult<List<Trip>> = error("Not used")
  }
}
