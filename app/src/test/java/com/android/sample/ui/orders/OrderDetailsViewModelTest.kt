package com.android.sample.ui.orders

// AI assistance: OpenAI Codex; trip-owner query tests (#147): Claude Code.

import androidx.lifecycle.viewModelScope
import com.commando.app.data.repository.OrderError
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.RatingError
import com.commando.app.data.repository.RatingRepository
import com.commando.app.data.repository.RatingResult
import com.commando.app.data.repository.TripError
import com.commando.app.data.repository.TripRepository
import com.commando.app.data.repository.TripResult
import com.commando.app.model.Location
import com.commando.app.model.NewRating
import com.commando.app.model.NewTrip
import com.commando.app.model.Order
import com.commando.app.model.OrderDecision
import com.commando.app.model.OrderDraft
import com.commando.app.model.OrderItem
import com.commando.app.model.OrderStatus
import com.commando.app.model.Rating
import com.commando.app.model.RatingSummary
import com.commando.app.model.Trip
import com.commando.app.model.TripStatus
import com.commando.app.model.TripTransition
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
import kotlinx.coroutines.test.TestScope
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
  fun cancellationKeepsConfirmedContentWhileSavingThenDisplaysPersistedSuccess() = runTest {
    val gate = CompletableDeferred<OrderResult<Order>>()
    orders.decision = { gate.await() }
    val vm = OrderDetailsViewModel(order.id, orders, trips, { order.requesterId })
    advanceUntilIdle()
    val initial = vm.uiState.value as OrderDetailsUiState.Content
    assertTrue(initial.canCancel)
    vm.cancel()
    runCurrent()
    val saving = vm.uiState.value as OrderDetailsUiState.Content
    assertEquals(order, saving.order)
    assertEquals(OrderMutationState.Saving, saving.mutation)
    assertTrue(saving.isSaving)
    val persisted = order.copy(status = OrderStatus.CANCELLED)
    gate.complete(OrderResult.Success(persisted))
    advanceUntilIdle()
    val saved = vm.uiState.value as OrderDetailsUiState.Content
    assertEquals(persisted, saved.order)
    assertEquals(initial.trip, saved.trip)
    assertEquals(OrderMutationState.Saved, saved.mutation)
    assertTrue(!saved.canCancel && !saved.isSaving)
    vm.cancel()
    advanceUntilIdle()
    assertEquals(1, orders.cancellations)
    assertEquals(listOf(order.id), orders.ids)
  }

  @Test
  fun cancellationFailurePreservesPendingOrderAndAnotherCancelRetriesSuccessfully() = runTest {
    for (throws in listOf(false, true)) {
      orders.cancellations = 0
      orders.decision = {
        if (throws) throw IllegalStateException("offline")
        OrderResult.Error(OrderError.Unknown)
      }
      val vm = OrderDetailsViewModel(order.id, orders, trips, { order.requesterId })
      advanceUntilIdle()
      vm.cancel()
      advanceUntilIdle()
      val failed = vm.uiState.value as OrderDetailsUiState.Content
      assertEquals(order, failed.order)
      assertEquals(OrderMutationState.Failed(OrderError.Unknown), failed.mutation)
      assertTrue(failed.canCancel && !failed.isSaving)
      val persisted = order.copy(status = OrderStatus.CANCELLED)
      orders.decision = { OrderResult.Success(persisted) }
      vm.cancel()
      advanceUntilIdle()
      val recovered = vm.uiState.value as OrderDetailsUiState.Content
      assertEquals(persisted, recovered.order)
      assertEquals(OrderMutationState.Saved, recovered.mutation)
      assertTrue(!recovered.canCancel && !recovered.isSaving)
      assertEquals(2, orders.cancellations)
    }
  }

  @Test
  fun accountChangeDuringCancellationDiscardsLateSuccessAndPreventsRetry() = runTest {
    val session = kotlinx.coroutines.flow.MutableStateFlow<String?>(order.requesterId)
    val gate = CompletableDeferred<OrderResult<Order>>()
    orders.decision = { gate.await() }
    val vm = OrderDetailsViewModel(order.id, orders, trips, { session.value }, session)
    advanceUntilIdle()
    vm.cancel()
    runCurrent()
    session.value = "another-requester"
    runCurrent()
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
    gate.complete(OrderResult.Success(order.copy(status = OrderStatus.CANCELLED)))
    advanceUntilIdle()
    vm.cancel()
    vm.retry()
    advanceUntilIdle()
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
    assertEquals(1, orders.cancellations)
    vm.viewModelScope.cancel()
  }

  @Test
  fun cancellationPreventsDuplicateSubmissionAndShowsPersistedConflict() = runTest {
    for (status in listOf(OrderStatus.ACCEPTED, OrderStatus.REJECTED)) {
      orders.cancellations = 0
      orders.ids.clear()
      val gate = CompletableDeferred<OrderResult<Order>>()
      orders.decision = { gate.await() }
      val vm = OrderDetailsViewModel(order.id, orders, trips, { order.requesterId })
      advanceUntilIdle()
      vm.cancel()
      vm.cancel()
      runCurrent()
      assertEquals(1, orders.cancellations)
      assertTrue((vm.uiState.value as OrderDetailsUiState.Content).isSaving)
      val persisted = order.copy(status = status)
      gate.complete(OrderResult.Error(OrderError.AlreadyDecided(persisted)))
      advanceUntilIdle()
      val content = vm.uiState.value as OrderDetailsUiState.Content
      assertEquals(persisted, content.order)
      assertEquals(
          OrderMutationState.Failed(OrderError.AlreadyDecided(persisted)),
          content.mutation,
      )
      assertEquals(listOf(order.id), orders.ids)
      assertTrue(!content.canCancel)
      assertTrue(!content.isSaving)
    }
  }

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

  @Test
  fun decisionEligibilityExcludesOtherUsersFinalOrdersSelfOrdersAndMismatchedTrips() = runTest {
    for ((viewer, candidate, associatedTrip) in
        listOf(
            Triple("requester", order, trip),
            Triple("stranger", order, trip),
            Triple(null, order, trip),
            Triple(trip.ownerId, order.copy(status = OrderStatus.ACCEPTED), trip),
            Triple(trip.ownerId, order.copy(status = OrderStatus.REJECTED), trip),
            Triple(trip.ownerId, order.copy(requesterId = trip.ownerId), trip),
            Triple(trip.ownerId, order, trip.copy(id = "wrong-trip")),
        )) {
      orders.lookup = { OrderResult.Success(candidate) }
      trips.lookup = { TripResult.Success(associatedTrip) }
      val vm = OrderDetailsViewModel(order.id, orders, trips, { viewer })
      advanceUntilIdle()
      assertTrue(!(vm.uiState.value as OrderDetailsUiState.Content).canDecide)
      vm.decide(OrderDecision.ACCEPT)
      advanceUntilIdle()
      assertTrue(orders.decisions.isEmpty())
    }
  }

  @Test
  fun staleDecisionShowsChangedContentAndTheNextAttemptUsesItsRevision() = runTest {
    val edited = order.copy(commissionCents = 8000, revision = 1)
    orders.decision = { OrderResult.Error(OrderError.AlreadyDecided(edited)) }
    val vm = OrderDetailsViewModel(order.id, orders, trips, { trip.ownerId })
    advanceUntilIdle()
    vm.decide(OrderDecision.ACCEPT)
    advanceUntilIdle()
    assertEquals(edited, (vm.uiState.value as OrderDetailsUiState.Content).order)
    orders.decision = { OrderResult.Success(edited.copy(status = OrderStatus.ACCEPTED)) }
    vm.decide(OrderDecision.ACCEPT)
    advanceUntilIdle()
    assertEquals(listOf(0, 1), orders.revisions)
  }

  @Test
  fun bothDecisionsUsePersistedResultAndBlockRepeatedTapsAndRetryWhileSaving() = runTest {
    for ((decision, status) in
        listOf(
            OrderDecision.ACCEPT to OrderStatus.ACCEPTED,
            OrderDecision.REJECT to OrderStatus.REJECTED,
        )) {
      orders.decisions.clear()
      val gate = CompletableDeferred<OrderResult<Order>>()
      orders.decision = { gate.await() }
      val vm = OrderDetailsViewModel(order.id, orders, trips, { trip.ownerId })
      advanceUntilIdle()
      assertTrue((vm.uiState.value as OrderDetailsUiState.Content).canDecide)
      vm.decide(decision)
      assertTrue((vm.uiState.value as OrderDetailsUiState.Content).isSaving)
      repeat(3) {
        vm.decide(decision)
        vm.retry()
      }
      runCurrent()
      assertEquals(listOf(order.id to decision), orders.decisions)
      val persisted = order.copy(status = status, commissionCents = 999)
      gate.complete(OrderResult.Success(persisted))
      advanceUntilIdle()
      val content = vm.uiState.value as OrderDetailsUiState.Content
      assertEquals(persisted, content.order)
      assertTrue(
          content.mutation == OrderMutationState.Saved && !content.canDecide && !content.isSaving
      )
      vm.decide(decision)
      advanceUntilIdle()
      assertEquals(1, orders.decisions.size)
    }
  }

  @Test
  fun conflictSynchronizesAuthoritativeOrderWithoutAnotherLookup() = runTest {
    val persisted = order.copy(status = OrderStatus.REJECTED)
    orders.decision = { OrderResult.Error(OrderError.AlreadyDecided(persisted)) }
    val vm = OrderDetailsViewModel(order.id, orders, trips, { trip.ownerId })
    advanceUntilIdle()
    vm.decide(OrderDecision.ACCEPT)
    advanceUntilIdle()
    val content = vm.uiState.value as OrderDetailsUiState.Content
    assertEquals(persisted, content.order)
    assertEquals(OrderMutationState.Failed(OrderError.AlreadyDecided(persisted)), content.mutation)
    assertTrue(!content.canDecide && !content.isSaving)
    assertEquals(1, orders.ids.size)
  }

  @Test
  fun failuresClearProtectedDataOrAllowRetryAsAppropriate() = runTest {
    for (error in
        listOf(
            OrderError.NotFound,
            OrderError.NotAuthenticated,
            OrderError.PermissionDenied,
            OrderError.Unknown,
        )) {
      orders.decision = { OrderResult.Error(error) }
      val vm = OrderDetailsViewModel(order.id, orders, trips, { trip.ownerId })
      advanceUntilIdle()
      vm.decide(OrderDecision.REJECT)
      advanceUntilIdle()
      when (error) {
        OrderError.NotFound -> assertEquals(OrderDetailsUiState.NotFound, vm.uiState.value)
        OrderError.Unknown -> {
          val content = vm.uiState.value as OrderDetailsUiState.Content
          assertEquals(OrderMutationState.Failed(error), content.mutation)
          assertTrue(content.canDecide && !content.isSaving)
          orders.decision = { OrderResult.Success(order.copy(status = OrderStatus.REJECTED)) }
          vm.decide(OrderDecision.REJECT)
          advanceUntilIdle()
          assertEquals(
              OrderMutationState.Saved,
              (vm.uiState.value as OrderDetailsUiState.Content).mutation,
          )
        }
        else -> assertEquals(OrderDetailsUiState.Error.OrderFailure(error), vm.uiState.value)
      }
    }
  }

  @Test
  fun signOutPermanentlyEndsSessionDespiteOriginalAccountReturningAndLateDecision() = runTest {
    val session = kotlinx.coroutines.flow.MutableStateFlow<String?>(trip.ownerId)
    val gate = CompletableDeferred<OrderResult<Order>>()
    orders.decision = { gate.await() }
    val vm = OrderDetailsViewModel(order.id, orders, trips, { session.value }, session)
    advanceUntilIdle()
    vm.decide(OrderDecision.ACCEPT)
    runCurrent()
    assertTrue((vm.uiState.value as OrderDetailsUiState.Content).isSaving)
    session.value = null
    runCurrent()
    val expected = OrderDetailsUiState.SessionEnded
    assertEquals(expected, vm.uiState.value)
    session.value = trip.ownerId
    runCurrent()
    vm.retry()
    vm.decide(OrderDecision.REJECT)
    runCurrent()
    assertEquals(expected, vm.uiState.value)
    gate.complete(OrderResult.Success(order.copy(status = OrderStatus.ACCEPTED)))
    advanceUntilIdle()
    assertEquals(expected, vm.uiState.value)
    vm.decide(OrderDecision.REJECT)
    vm.retry()
    advanceUntilIdle()
    assertEquals(1, orders.decisions.size)
    assertEquals(1, orders.ids.size)
    assertEquals(expected, vm.uiState.value)
    vm.viewModelScope.cancel()
  }

  @Test
  fun accountSwitchDuringLoadCannotBeUndoneByReturningOrLateContent() = runTest {
    val session = kotlinx.coroutines.flow.MutableStateFlow<String?>(trip.ownerId)
    val gate = CompletableDeferred<OrderResult<Order>>()
    orders.lookup = { gate.await() }
    val vm = OrderDetailsViewModel(order.id, orders, trips, { session.value }, session)
    runCurrent()
    session.value = "another-owner"
    runCurrent()
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
    session.value = trip.ownerId
    runCurrent()
    gate.complete(OrderResult.Success(order))
    advanceUntilIdle()
    vm.retry()
    vm.decide(OrderDecision.ACCEPT)
    advanceUntilIdle()
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
    assertEquals(1, orders.ids.size)
    assertTrue(orders.decisions.isEmpty())
    vm.viewModelScope.cancel()
  }

  @Test
  fun thrownDecisionFailureAllowsRetryAndCancelledDestinationIgnoresLateResult() = runTest {
    orders.decision = { throw IllegalStateException("offline") }
    val vm = OrderDetailsViewModel(order.id, orders, trips, { trip.ownerId })
    advanceUntilIdle()
    vm.decide(OrderDecision.ACCEPT)
    advanceUntilIdle()
    val content = vm.uiState.value as OrderDetailsUiState.Content
    assertEquals(OrderMutationState.Failed(OrderError.Unknown), content.mutation)
    assertTrue(content.canDecide && !content.isSaving)
    val gate = LateResult<OrderResult<Order>>()
    orders.decision = { gate.await() }
    vm.decide(OrderDecision.ACCEPT)
    runCurrent()
    val saving = vm.uiState.value
    vm.viewModelScope.cancel()
    gate.complete(OrderResult.Success(order.copy(status = OrderStatus.ACCEPTED)))
    advanceUntilIdle()
    assertEquals(saving, vm.uiState.value)
  }

  @Test
  fun sessionSnapshotChangeBeforeTapPreventsRepositoryCall() = runTest {
    var viewer: String? = trip.ownerId
    val vm = OrderDetailsViewModel(order.id, orders, trips, { viewer })
    advanceUntilIdle()
    viewer = null
    vm.decide(OrderDecision.ACCEPT)
    assertEquals(
        OrderDetailsUiState.SessionEnded,
        vm.uiState.value,
    )
    assertTrue(orders.decisions.isEmpty())
  }

  @Test
  fun readinessEligibilitySavingRecoveryAndSessionLossUseConfirmedContent() = runTest {
    val accepted = order.copy(status = OrderStatus.ACCEPTED)
    for (status in OrderStatus.entries) for (viewer in
        listOf(trip.ownerId, "requester", "stranger", null)) {
      val content = OrderDetailsUiState.Content(order.copy(status = status), trip, viewer)
      assertEquals(
          viewer == trip.ownerId && status == OrderStatus.ACCEPTED,
          content.canMarkReadyForHandoff,
      )
    }
    orders.lookup = { OrderResult.Success(accepted) }
    val session = kotlinx.coroutines.flow.MutableStateFlow<String?>(trip.ownerId)
    val vm = OrderDetailsViewModel(order.id, orders, trips, { session.value }, session)
    advanceUntilIdle()
    for (error in
        listOf(
            OrderError.Unknown,
            OrderError.AlreadyDecided(accepted.copy(status = OrderStatus.REJECTED)),
        )) {
      orders.decision = { OrderResult.Error(error) }
      vm.markReadyForHandoff()
      advanceUntilIdle()
      val confirmed = if (error is OrderError.AlreadyDecided) error.persistedOrder else accepted
      assertEquals(
          OrderDetailsUiState.Content(
              confirmed,
              trip,
              trip.ownerId,
              OrderMutationState.Failed(error),
          ),
          vm.uiState.value,
      )
    }
    vm.retry()
    advanceUntilIdle()
    val gate = CompletableDeferred<OrderResult<Order>>()
    orders.decision = { gate.await() }
    vm.markReadyForHandoff()
    val saving = vm.uiState.value as OrderDetailsUiState.Content
    assertEquals(OrderMutationState.Saving, saving.mutation)
    assertEquals(accepted, saving.order)
    repeat(3) {
      vm.markReadyForHandoff()
      vm.decide(OrderDecision.REJECT)
      vm.retry()
    }
    runCurrent()
    assertEquals(listOf(order.id, order.id, order.id), orders.readyIds)
    assertTrue(orders.decisions.isEmpty())
    val ready = accepted.copy(status = OrderStatus.READY_FOR_HANDOFF)
    gate.complete(OrderResult.Success(ready))
    advanceUntilIdle()
    assertEquals(
        OrderDetailsUiState.Content(ready, trip, trip.ownerId, OrderMutationState.Saved),
        vm.uiState.value,
    )
    vm.markReadyForHandoff()
    assertEquals(3, orders.readyIds.size)
    for (error in listOf(OrderError.NotAuthenticated, OrderError.PermissionDenied)) {
      vm.retry()
      advanceUntilIdle()
      orders.decision = { OrderResult.Error(error) }
      vm.markReadyForHandoff()
      advanceUntilIdle()
      assertEquals(OrderDetailsUiState.Error.OrderFailure(error), vm.uiState.value)
    }
    orders.lookup = { OrderResult.Success(accepted) }
    vm.retry()
    advanceUntilIdle()
    val late = CompletableDeferred<OrderResult<Order>>()
    orders.decision = { late.await() }
    vm.markReadyForHandoff()
    runCurrent()
    session.value = "another-owner"
    runCurrent()
    late.complete(OrderResult.Success(ready))
    advanceUntilIdle()
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
    vm.viewModelScope.cancel()
  }

  @Test
  fun receiptEligibilityRejectsWrongViewerStatusAndTrip() = runTest {
    val ready = order.copy(status = OrderStatus.READY_FOR_HANDOFF)
    assertTrue(OrderDetailsUiState.Content(ready, trip, order.requesterId).canConfirmReceipt)
    for (content in
        listOf(
            OrderDetailsUiState.Content(ready, trip, trip.ownerId),
            OrderDetailsUiState.Content(ready, trip, "stranger"),
            OrderDetailsUiState.Content(ready, trip),
            OrderDetailsUiState.Content(ready, trip.copy(id = "wrong"), order.requesterId),
        ) +
            listOf(
                    OrderStatus.PENDING,
                    OrderStatus.ACCEPTED,
                    OrderStatus.REJECTED,
                    OrderStatus.DELIVERED,
                )
                .map {
                  OrderDetailsUiState.Content(order.copy(status = it), trip, order.requesterId)
                }) assertTrue(!content.canConfirmReceipt)
    orders.lookup = { OrderResult.Success(ready) }
    val vm = OrderDetailsViewModel(order.id, orders, trips, { trip.ownerId })
    advanceUntilIdle()
    vm.confirmReceipt()
    advanceUntilIdle()
    assertTrue(orders.receiptIds.isEmpty())
  }

  @Test
  fun receiptRetriesFailuresBlocksDuplicateSubmissionsAndUsesConfirmedResults() = runTest {
    val ready = order.copy(status = OrderStatus.READY_FOR_HANDOFF)
    orders.lookup = { OrderResult.Success(ready) }
    val vm = OrderDetailsViewModel(order.id, orders, trips, { order.requesterId })
    advanceUntilIdle()
    orders.decision = { OrderResult.Error(OrderError.Unknown) }
    vm.confirmReceipt()
    advanceUntilIdle()
    assertEquals(
        OrderDetailsUiState.Content(
            ready,
            trip,
            order.requesterId,
            OrderMutationState.Failed(OrderError.Unknown),
        ),
        vm.uiState.value,
    )
    val gate = CompletableDeferred<OrderResult<Order>>()
    orders.decision = { gate.await() }
    vm.confirmReceipt()
    repeat(3) {
      vm.confirmReceipt()
      vm.retry()
      vm.markReadyForHandoff()
    }
    runCurrent()
    assertEquals(2, orders.receiptIds.size)
    assertEquals(
        OrderDetailsUiState.Content(ready, trip, order.requesterId, OrderMutationState.Saving),
        vm.uiState.value,
    )
    val delivered = ready.copy(status = OrderStatus.DELIVERED)
    gate.complete(OrderResult.Success(delivered))
    advanceUntilIdle()
    assertEquals(
        OrderDetailsUiState.Content(delivered, trip, order.requesterId, OrderMutationState.Saved),
        vm.uiState.value,
    )
    vm.confirmReceipt()
    assertEquals(2, orders.receiptIds.size)
  }

  @Test
  fun receiptConflictsAndSessionChangesCannotLeaveStaleActionsOrResults() = runTest {
    val ready = order.copy(status = OrderStatus.READY_FOR_HANDOFF)
    val delivered = ready.copy(status = OrderStatus.DELIVERED)
    orders.lookup = { OrderResult.Success(ready) }
    orders.decision = { OrderResult.Error(OrderError.AlreadyDecided(delivered)) }
    val vm = OrderDetailsViewModel(order.id, orders, trips, { order.requesterId })
    advanceUntilIdle()
    vm.confirmReceipt()
    advanceUntilIdle()
    assertEquals(
        OrderDetailsUiState.Content(
            delivered,
            trip,
            order.requesterId,
            OrderMutationState.Failed(OrderError.AlreadyDecided(delivered)),
        ),
        vm.uiState.value,
    )
    for (nextUser in listOf(null, "another-requester")) {
      val session = kotlinx.coroutines.flow.MutableStateFlow<String?>(order.requesterId)
      val late = CompletableDeferred<OrderResult<Order>>()
      orders.decision = { late.await() }
      val pending = OrderDetailsViewModel(order.id, orders, trips, { session.value }, session)
      advanceUntilIdle()
      pending.confirmReceipt()
      runCurrent()
      session.value = nextUser
      runCurrent()
      late.complete(OrderResult.Success(delivered))
      advanceUntilIdle()
      assertEquals(OrderDetailsUiState.SessionEnded, pending.uiState.value)
      pending.viewModelScope.cancel()
    }
  }

  @Test
  fun ratingOnlyFollowsSuccessfulMatchingReceiptAndDismissalCannotReopenIt() = runTest {
    val delivered = order.copy(status = OrderStatus.DELIVERED)
    for (result in
        listOf(
            OrderResult.Error(OrderError.Unknown),
            OrderResult.Error(OrderError.AlreadyDecided(delivered)),
            OrderResult.Success(delivered.copy(id = "wrong")),
            OrderResult.Success(delivered.copy(tripId = "wrong")),
            OrderResult.Success(delivered.copy(requesterId = trip.ownerId)),
            OrderResult.Success(order),
            OrderResult.Success(delivered),
        )) {
      val vm = ratingVm(TestRatings())
      advanceUntilIdle()
      assertTrue(!vm.ratingState.value.visible)
      orders.decision = { result }
      vm.confirmReceipt()
      advanceUntilIdle()
      assertEquals(result == OrderResult.Success(delivered), vm.ratingState.value.visible)
      vm.dismissRating()
      vm.confirmReceipt()
      vm.retry()
      advanceUntilIdle()
      assertTrue(!vm.ratingState.value.visible)
      vm.viewModelScope.cancel()
    }
    orders.lookup = { OrderResult.Success(delivered) }
    val initial = OrderDetailsViewModel(order.id, orders, trips, { order.requesterId })
    advanceUntilIdle()
    assertTrue(!initial.ratingState.value.visible)
  }

  @Test fun ratingRequiresSelectedScore() = assertInvalidRatingInput(0, "")

  @Test fun ratingRejectsCommentOver250Utf16Units() = assertInvalidRatingInput(1, "x".repeat(251))

  @Test fun ratingRejects126SurrogatePairEmoji() = assertInvalidRatingInput(5, "😀".repeat(126))

  @Test fun ratingAcceptsEmptyComment() = assertValidRatingComment("")

  @Test fun ratingAcceptsLongBlankComment() = assertValidRatingComment(" ".repeat(251))

  @Test fun ratingAccepts250CharacterComment() = assertValidRatingComment("x".repeat(250))

  @Test fun ratingAccepts125SurrogatePairEmoji() = assertValidRatingComment("😀".repeat(125))

  @Test
  fun ratingScoreSelectionIgnoresOutOfRangeValues() = runTest {
    val vm = confirmedRatingVm(TestRatings())
    vm.selectScore(5)
    for (invalid in listOf(-1, 0, 6)) {
      vm.selectScore(invalid)
      assertEquals("Invalid score: $invalid", 5, vm.ratingState.value.score)
    }
    vm.viewModelScope.cancel()
  }

  @Test
  fun ratingSubmissionRetainsInputAcrossPendingFailure() = runTest {
    val ratings = TestRatings()
    val vm = confirmedRatingVm(ratings)
    vm.selectScore(5)
    vm.updateComment(" preserved ")
    val gate = CompletableDeferred<RatingResult<Rating>>()
    ratings.save = { gate.await() }
    vm.submitRating()
    vm.dismissRating()
    vm.selectScore(1)
    vm.updateComment("lost")
    vm.retry()
    runCurrent()
    assertEquals(listOf(NewRating(order.id, 5, " preserved ")), ratings.inputs)
    assertTrue(vm.ratingState.value.submitting)
    assertEquals(" preserved ", vm.ratingState.value.comment)
    assertEquals(5, vm.ratingState.value.score)
    gate.complete(RatingResult.Error(RatingError.Unknown))
    advanceUntilIdle()
    assertEquals(" preserved ", vm.ratingState.value.comment)
    assertTrue(vm.ratingState.value.visible && !vm.ratingState.value.submitting)
    vm.viewModelScope.cancel()
  }

  @Test
  fun ratingSubmissionIgnoresDuplicateClicksWhilePending() = runTest {
    val ratings = TestRatings()
    val vm = confirmedRatingVm(ratings)
    vm.selectScore(5)
    vm.updateComment(" preserved ")
    val gate = CompletableDeferred<RatingResult<Rating>>()
    ratings.save = { gate.await() }
    vm.submitRating()
    vm.submitRating()
    runCurrent()
    assertEquals(listOf(NewRating(order.id, 5, " preserved ")), ratings.inputs)
    assertTrue(vm.ratingState.value.submitting)
    gate.complete(RatingResult.Success(ratings.saved))
    advanceUntilIdle()
    vm.viewModelScope.cancel()
  }

  @Test
  fun ratingRetryRetainsFailedInputAndClosesAfterSuccess() = runTest {
    val ratings = TestRatings()
    val vm = confirmedRatingVm(ratings)
    vm.selectScore(5)
    vm.updateComment(" preserved ")
    ratings.save = { RatingResult.Error(RatingError.Unknown) }
    vm.submitRating()
    advanceUntilIdle()
    assertEquals(" preserved ", vm.ratingState.value.comment)
    assertTrue(vm.ratingState.value.visible && !vm.ratingState.value.submitting)
    ratings.save = { RatingResult.Success(ratings.saved) }
    vm.submitRating()
    advanceUntilIdle()
    assertTrue(vm.ratingState.value.submitted && !vm.ratingState.value.visible)
    vm.submitRating()
    assertEquals(2, ratings.inputs.size)
    assertEquals(
        OrderStatus.DELIVERED,
        (vm.uiState.value as OrderDetailsUiState.Content).order.status,
    )
    vm.viewModelScope.cancel()
  }

  @Test
  fun uncertainWritesReconcileAbsenceExistingFeedbackAndReadFailuresBeforeRetry() = runTest {
    for (existing in
        listOf(
            RatingResult.Success(null),
            RatingResult.Success(TestRatings().saved),
            RatingResult.Error(RatingError.Unknown),
        )) {
      val ratings = TestRatings()
      val vm = ratingVm(ratings)
      advanceUntilIdle()
      vm.confirmReceipt()
      advanceUntilIdle()
      vm.selectScore(4)
      ratings.save = { RatingResult.Error(RatingError.Unknown) }
      vm.submitRating()
      advanceUntilIdle()
      ratings.read = { existing }
      ratings.save = { RatingResult.Error(RatingError.AlreadyRated) }
      vm.submitRating()
      advanceUntilIdle()
      // AlreadyRated after an absent reconciliation read triggers a lookup of the winning rating.
      assertEquals(if (existing == RatingResult.Success(null)) 2 else 1, ratings.reads)
      assertEquals(if (existing == RatingResult.Success(null)) 2 else 1, ratings.inputs.size)
      assertEquals(existing is RatingResult.Error, vm.ratingState.value.visible)
      assertEquals(existing == RatingResult.Success(ratings.saved), vm.ratingState.value.submitted)
      if (existing == RatingResult.Success(null))
          assertEquals(RatingError.AlreadyRated, vm.ratingState.value.error)
      vm.dismissRating()
      vm.submitRating()
      assertTrue(!vm.ratingState.value.visible)
      vm.viewModelScope.cancel()
    }
  }

  @Test fun uncertainRatingReconcilesEmptyComment() = assertMatchingRatingReconciles("", null)

  @Test
  fun uncertainRatingReconcilesWhitespaceOnlyComment() =
      assertMatchingRatingReconciles(" \t\n", null)

  @Test
  fun uncertainRatingReconcilesLongBlankComment() =
      assertMatchingRatingReconciles(" ".repeat(251), null)

  @Test
  fun uncertainRatingReconcilesVerbatimNonblankComment() =
      assertMatchingRatingReconciles(" exact ", " exact ")

  @Test
  fun uncertainRatingRejectsDifferentOrder() = assertMismatchedRatingRejected {
    it.copy(orderId = "other")
  }

  @Test
  fun uncertainRatingRejectsDifferentScore() = assertMismatchedRatingRejected { it.copy(score = 1) }

  @Test
  fun uncertainRatingRejectsDifferentComment() = assertMismatchedRatingRejected {
    it.copy(comment = "other")
  }

  @Test
  fun uncertainRatingRejectsTrimmedComment() = assertMismatchedRatingRejected {
    it.copy(comment = "exact")
  }

  @Test
  fun uncertainRatingRejectsMissingComment() = assertMismatchedRatingRejected {
    it.copy(comment = null)
  }

  @Test
  fun ratingIdentityStaysInvalidAfterOriginalAccountReturns() = runTest {
    val ratings = TestRatings()
    val session = kotlinx.coroutines.flow.MutableStateFlow<String?>(order.requesterId)
    val vm = confirmedRatingVm(ratings, session)
    vm.selectScore(4)
    vm.submitRating()
    advanceUntilIdle()
    assertEquals(order.requesterId, ratings.identity())
    session.value = null
    runCurrent()
    session.value = order.requesterId
    runCurrent()
    assertEquals(null, ratings.identity())
    assertEquals(RatingDialogState(), vm.ratingState.value)
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
    vm.viewModelScope.cancel()
  }

  @Test
  fun lateRatingSubmissionCannotRestoreStateAfterOriginalAccountReturns() =
      assertLateRatingResultIgnored(duringRead = false)

  @Test
  fun lateRatingReconciliationCannotRestoreStateAfterOriginalAccountReturns() =
      assertLateRatingResultIgnored(duringRead = true)

  @Test
  fun terminalRatingErrorsBlockEditsAndResubmissionButAllowDismissal() = runTest {
    for (error in
        listOf(
            RatingError.PermissionDenied,
            RatingError.NotFound,
            RatingError.OrderNotDelivered,
            RatingError.InvalidData,
            RatingError.AlreadyRated,
        )) {
      val ratings = TestRatings().apply { save = { RatingResult.Error(error) } }
      val vm = ratingVm(ratings)
      advanceUntilIdle()
      vm.confirmReceipt()
      advanceUntilIdle()
      vm.selectScore(4)
      vm.submitRating()
      advanceUntilIdle()
      val failed = vm.ratingState.value
      vm.selectScore(1)
      vm.updateComment("ignored")
      vm.submitRating()
      assertEquals(failed, vm.ratingState.value)
      assertEquals(1, ratings.inputs.size)
      vm.dismissRating()
      assertTrue(!vm.ratingState.value.visible)
      vm.viewModelScope.cancel()
    }
  }

  @Test
  fun deliveredRequesterLoadsExistingRatingWithoutOpeningDialog() = runTest {
    val ratings = TestRatings()
    val gate = CompletableDeferred<RatingResult<Rating?>>()
    ratings.read = { gate.await() }
    val vm = ratingVm(ratings, status = OrderStatus.DELIVERED)
    runCurrent()
    val initial = vm.uiState.value as OrderDetailsUiState.Content
    assertEquals(OrderStatus.DELIVERED, initial.order.status)
    assertEquals(null, initial.submittedRating)
    gate.complete(RatingResult.Success(ratings.saved))
    advanceUntilIdle()
    assertEquals(initial.copy(submittedRating = ratings.saved), vm.uiState.value)
    assertEquals(listOf(order.id), ratings.readIds)
    assertEquals(RatingDialogState(), vm.ratingState.value)
    vm.viewModelScope.cancel()
  }

  @Test
  fun absentOrFailedHistoricalRatingPreservesDeliveredDetails() = runTest {
    for (read in
        listOf<suspend () -> RatingResult<Rating?>>(
            { RatingResult.Success(null) },
            { RatingResult.Error(RatingError.Unknown) },
            { throw IllegalStateException("offline") },
        )) {
      val ratings = TestRatings().apply { this.read = read }
      val vm = ratingVm(ratings, status = OrderStatus.DELIVERED)
      advanceUntilIdle()
      assertEquals(
          OrderDetailsUiState.Content(
              order.copy(status = OrderStatus.DELIVERED),
              trip,
              order.requesterId,
          ),
          vm.uiState.value,
      )
      assertEquals(listOf(order.id), ratings.readIds)
      assertEquals(RatingDialogState(), vm.ratingState.value)
      vm.viewModelScope.cancel()
    }
  }

  @Test
  fun nonDeliveredOrdersNeverInitializeOrQueryRatings() = runTest {
    for (status in OrderStatus.entries.filter { it != OrderStatus.DELIVERED }) {
      val ratings = TestRatings()
      val vm = ratingVm(ratings, status = status)
      advanceUntilIdle()
      assertEquals(status, (vm.uiState.value as OrderDetailsUiState.Content).order.status)
      assertEquals(0, ratings.reads)
      assertTrue(!ratings.initialized)
      assertTrue(ratings.inputs.isEmpty())
      vm.viewModelScope.cancel()
    }
  }

  @Test
  fun ineligibleDeliveredOrdersNeverInitializeOrQueryRatings() = runTest {
    val delivered = order.copy(status = OrderStatus.DELIVERED)
    for ((viewer, candidate, associatedTrip) in
        listOf(
            Triple(trip.ownerId, delivered, trip),
            Triple("outsider", delivered, trip),
            Triple(null, delivered, trip),
            Triple(trip.ownerId, delivered.copy(requesterId = trip.ownerId), trip),
            Triple(order.requesterId, delivered, trip.copy(id = "wrong-trip")),
            Triple(order.requesterId, delivered.copy(id = "wrong-order"), trip),
        )) {
      val ratings = TestRatings()
      val session = kotlinx.coroutines.flow.MutableStateFlow<String?>(viewer)
      val vm = ratingVm(ratings, session, OrderStatus.DELIVERED)
      orders.lookup = { OrderResult.Success(candidate) }
      trips.lookup = { TripResult.Success(associatedTrip) }
      advanceUntilIdle()
      assertEquals(
          OrderDetailsUiState.Content(candidate, associatedTrip, viewer),
          vm.uiState.value,
      )
      assertTrue(!ratings.initialized)
      assertEquals(0, ratings.reads)
      assertTrue(ratings.readIds.isEmpty())
      assertTrue(ratings.inputs.isEmpty())
      vm.viewModelScope.cancel()
    }
  }

  @Test
  fun changedIdentitySnapshotDiscardsHistoricalRatingBeforeAuthEventArrives() = runTest {
    val session = kotlinx.coroutines.flow.MutableStateFlow<String?>(order.requesterId)
    var userId: String? = order.requesterId
    val gate = CompletableDeferred<RatingResult<Rating?>>()
    val ratings = TestRatings().apply { read = { gate.await() } }
    val vm = ratingVm(ratings, session, OrderStatus.DELIVERED, { userId })
    runCurrent()
    assertEquals(listOf(order.id), ratings.readIds)
    assertEquals(null, (vm.uiState.value as OrderDetailsUiState.Content).submittedRating)
    userId = "another-requester"
    assertEquals(order.requesterId, session.value)
    gate.complete(RatingResult.Success(ratings.saved))
    advanceUntilIdle()
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
    assertEquals(RatingDialogState(), vm.ratingState.value)
    userId = order.requesterId
    vm.retry()
    advanceUntilIdle()
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
    assertEquals(listOf(order.id), ratings.readIds)
    vm.viewModelScope.cancel()
  }

  @Test
  fun changedIdentitySnapshotPreventsQueuedHistoricalLookupWithoutAuthEvent() = runTest {
    val session = kotlinx.coroutines.flow.MutableStateFlow<String?>(order.requesterId)
    var userId: String? = order.requesterId
    val ratings = TestRatings()
    trips.lookup = {
      // Queue the account change before the rating coroutine, without notifying session observers.
      backgroundScope.launch { userId = "another-requester" }
      TripResult.Success(trip)
    }
    val vm = ratingVm(ratings, session, OrderStatus.DELIVERED, { userId })
    runCurrent()
    assertEquals("another-requester", userId)
    assertEquals(order.requesterId, session.value)
    assertEquals(listOf(trip.id), trips.ids)
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
    assertTrue(!ratings.initialized)
    assertTrue(ratings.readIds.isEmpty())
    assertTrue(ratings.inputs.isEmpty())
    assertEquals(RatingDialogState(), vm.ratingState.value)
    vm.viewModelScope.cancel()
  }

  @Test
  fun accountChangeClearsLoadedRatingAndOriginalAccountCannotRestoreIt() = runTest {
    val ratings = TestRatings().apply { read = { RatingResult.Success(saved) } }
    val session = kotlinx.coroutines.flow.MutableStateFlow<String?>(order.requesterId)
    val vm = ratingVm(ratings, session, OrderStatus.DELIVERED)
    advanceUntilIdle()
    assertEquals(ratings.saved, (vm.uiState.value as OrderDetailsUiState.Content).submittedRating)
    session.value = "another-requester"
    runCurrent()
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
    session.value = order.requesterId
    runCurrent()
    vm.retry()
    advanceUntilIdle()
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
    assertEquals(RatingDialogState(), vm.ratingState.value)
    assertEquals(1, ratings.reads)
    vm.viewModelScope.cancel()
  }

  @Test
  fun lateHistoricalRatingCannotRestorePreviousAccountContent() = runTest {
    val late = LateResult<RatingResult<Rating?>>()
    val ratings = TestRatings().apply { read = { late.await() } }
    val session = kotlinx.coroutines.flow.MutableStateFlow<String?>(order.requesterId)
    val vm = ratingVm(ratings, session, OrderStatus.DELIVERED)
    runCurrent()
    assertEquals(1, ratings.reads)
    session.value = null
    runCurrent()
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
    session.value = order.requesterId
    runCurrent()
    late.complete(RatingResult.Success(ratings.saved))
    advanceUntilIdle()
    assertEquals(1, late.returns)
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
    assertEquals(RatingDialogState(), vm.ratingState.value)
    vm.viewModelScope.cancel()
  }

  @Test
  fun successfulSubmissionPublishesRepositoryFeedbackWithoutReloading() = runTest {
    val ratings = TestRatings()
    val vm = confirmedRatingVm(ratings)
    val saved = ratings.saved.copy(comment = " preserved feedback ")
    ratings.save = { RatingResult.Success(saved) }
    vm.selectScore(saved.score)
    vm.updateComment(saved.comment!!)
    vm.submitRating()
    advanceUntilIdle()
    assertEquals(saved, (vm.uiState.value as OrderDetailsUiState.Content).submittedRating)
    assertEquals(
        OrderMutationState.Saved,
        (vm.uiState.value as OrderDetailsUiState.Content).mutation,
    )
    assertTrue(vm.ratingState.value.submitted && !vm.ratingState.value.visible)
    assertEquals(0, ratings.reads)
    assertEquals(listOf(NewRating(order.id, saved.score, saved.comment)), ratings.inputs)
    vm.viewModelScope.cancel()
  }

  @Test
  fun cancelledHistoricalReadCannotOverwriteNewlySubmittedRating() = runTest {
    val late = LateResult<RatingResult<Rating?>>()
    val ratings = TestRatings().apply { read = { late.await() } }
    val vm = ratingVm(ratings, status = OrderStatus.DELIVERED)
    runCurrent()
    assertEquals(1, ratings.reads)
    // A refreshed order can still be ready while an older feedback lookup is in flight.
    orders.lookup = { OrderResult.Success(order.copy(status = OrderStatus.READY_FOR_HANDOFF)) }
    vm.retry()
    runCurrent()
    vm.confirmReceipt()
    runCurrent()
    assertTrue(vm.ratingState.value.visible)
    vm.selectScore(ratings.saved.score)
    vm.submitRating()
    runCurrent()
    assertEquals(ratings.saved, (vm.uiState.value as OrderDetailsUiState.Content).submittedRating)
    late.complete(RatingResult.Success(ratings.saved.copy(score = RATING_SCORE_RANGE.first)))
    advanceUntilIdle()
    assertEquals(1, late.returns)
    assertEquals(ratings.saved, (vm.uiState.value as OrderDetailsUiState.Content).submittedRating)
    assertTrue(vm.ratingState.value.submitted && !vm.ratingState.value.visible)
    vm.viewModelScope.cancel()
  }

  private fun TestScope.confirmedRatingVm(
      ratings: TestRatings,
      session: kotlinx.coroutines.flow.MutableStateFlow<String?> =
          kotlinx.coroutines.flow.MutableStateFlow(order.requesterId),
  ): OrderDetailsViewModel {
    val vm = ratingVm(ratings, session)
    advanceUntilIdle()
    vm.confirmReceipt()
    advanceUntilIdle()
    return vm
  }

  private fun assertInvalidRatingInput(score: Int, comment: String) = runTest {
    val ratings = TestRatings()
    val vm = confirmedRatingVm(ratings)
    vm.selectScore(score)
    vm.updateComment(comment)
    vm.submitRating()
    advanceUntilIdle()
    assertTrue(ratings.inputs.isEmpty())
    vm.viewModelScope.cancel()
  }

  private fun assertValidRatingComment(comment: String) = runTest {
    val vm = confirmedRatingVm(TestRatings())
    vm.selectScore(1)
    vm.updateComment(comment)
    assertTrue(vm.ratingState.value.valid)
    vm.viewModelScope.cancel()
  }

  private fun assertMatchingRatingReconciles(comment: String, savedComment: String?) = runTest {
    val ratings = TestRatings()
    val vm = confirmedRatingVm(ratings)
    vm.selectScore(4)
    vm.updateComment(comment)
    ratings.save = { RatingResult.Error(RatingError.Unknown) }
    vm.submitRating()
    advanceUntilIdle()
    ratings.read = { RatingResult.Success(ratings.saved.copy(comment = savedComment)) }
    vm.submitRating()
    advanceUntilIdle()
    assertTrue(vm.ratingState.value.submitted)
    assertTrue(!vm.ratingState.value.visible)
    assertEquals(null, vm.ratingState.value.error)
    assertEquals(1, ratings.reads)
    assertEquals(1, ratings.inputs.size)
    vm.viewModelScope.cancel()
  }

  private fun assertMismatchedRatingRejected(change: (Rating) -> Rating) = runTest {
    val ratings = TestRatings()
    val expected = ratings.saved.copy(comment = " exact ")
    val vm = confirmedRatingVm(ratings)
    vm.selectScore(expected.score)
    vm.updateComment(expected.comment!!)
    ratings.save = { RatingResult.Error(RatingError.Unknown) }
    vm.submitRating()
    advanceUntilIdle()
    ratings.read = { RatingResult.Success(change(expected)) }
    vm.submitRating()
    advanceUntilIdle()
    assertEquals(RatingError.AlreadyRated, vm.ratingState.value.error)
    assertTrue(!vm.ratingState.value.submitted && !vm.ratingState.value.visible)
    assertEquals(1, ratings.reads)
    assertEquals(1, ratings.inputs.size)
    vm.viewModelScope.cancel()
  }

  private fun assertLateRatingResultIgnored(duringRead: Boolean) = runTest {
    val ratings = TestRatings()
    val session = kotlinx.coroutines.flow.MutableStateFlow<String?>(order.requesterId)
    val vm = confirmedRatingVm(ratings, session)
    vm.selectScore(4)
    val late = LateResult<RatingResult<Rating>>()
    ratings.save = { if (duringRead) RatingResult.Error(RatingError.Unknown) else late.await() }
    vm.submitRating()
    runCurrent()
    if (duringRead) {
      ratings.read = { late.await() }
      vm.submitRating()
      runCurrent()
    }
    assertEquals(order.requesterId, ratings.identity())
    session.value = null
    runCurrent()
    session.value = order.requesterId
    runCurrent()
    assertEquals(null, ratings.identity())
    late.complete(RatingResult.Success(ratings.saved))
    advanceUntilIdle()
    assertEquals(RatingDialogState(), vm.ratingState.value)
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
    vm.viewModelScope.cancel()
  }

  private fun ratingVm(
      ratings: TestRatings,
      session: kotlinx.coroutines.flow.MutableStateFlow<String?> =
          kotlinx.coroutines.flow.MutableStateFlow(order.requesterId),
      status: OrderStatus = OrderStatus.READY_FOR_HANDOFF,
      currentUserId: () -> String? = { session.value },
  ): OrderDetailsViewModel {
    orders.lookup = { OrderResult.Success(order.copy(status = status)) }
    orders.decision = { OrderResult.Success(order.copy(status = OrderStatus.DELIVERED)) }
    return OrderDetailsViewModel(
        order.id,
        orders,
        trips,
        currentUserId,
        session,
        { identity ->
          ratings.also {
            it.identity = identity
            it.initialized = true
          }
        },
    )
  }

  private inner class TestRatings : RatingRepository {
    val saved = Rating(order.id, trip.id, order.requesterId, trip.ownerId, 4, null, now)
    val inputs = mutableListOf<NewRating>()
    var identity: () -> String? = { null }
    var reads = 0
    val readIds = mutableListOf<String>()
    var initialized = false
    var save: suspend () -> RatingResult<Rating> = { RatingResult.Success(saved) }
    var read: suspend () -> RatingResult<Rating?> = { RatingResult.Success(null) }

    override suspend fun submitRating(input: NewRating): RatingResult<Rating> {
      inputs += input
      return save()
    }

    override suspend fun getRatingForOrder(orderId: String): RatingResult<Rating?> {
      reads++
      readIds += orderId
      return read()
    }

    override suspend fun getRatingSummary(commandoId: String): RatingResult<RatingSummary> =
        error("Unused")
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
    override suspend fun editOrder(
        orderId: String,
        draft: OrderDraft,
        expectedRevision: Int,
    ): OrderResult<Order> = error("Not used")

    var cancellations = 0

    override suspend fun confirmReceipt(orderId: String): OrderResult<Order> {
      receiptIds += orderId
      return decision()
    }

    val ids = mutableListOf<String>()
    val receiptIds = mutableListOf<String>()
    val decisions = mutableListOf<Pair<String, OrderDecision>>()
    val revisions = mutableListOf<Int>()
    val readyIds = mutableListOf<String>()
    var decision: suspend () -> OrderResult<Order> = { error("Not configured") }
    var lookup: suspend () -> OrderResult<Order> = { OrderResult.Success(order) }

    override suspend fun getOrderById(orderId: String): OrderResult<Order> {
      ids += orderId
      return lookup()
    }

    override suspend fun createOrder(draft: OrderDraft): OrderResult<Order> = error("Not used")

    override suspend fun markReadyForHandoff(orderId: String): OrderResult<Order> {
      readyIds += orderId
      return decision()
    }

    override suspend fun decideOrder(
        orderId: String,
        decision: OrderDecision,
        expectedRevision: Int,
    ): OrderResult<Order> {
      decisions += orderId to decision
      revisions += expectedRevision
      return this.decision()
    }

    override suspend fun cancelOrder(orderId: String): OrderResult<Order> {
      cancellations++
      return decision()
    }

    override suspend fun getMyOrders(): OrderResult<List<Order>> = error("Not used")

    override suspend fun getOrdersForTrip(tripId: String): OrderResult<List<Order>> =
        error("Not used")
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

    override suspend fun transitionTrip(
        tripId: String,
        transition: TripTransition,
    ): TripResult<Trip> = error("Not used")
  }
}
