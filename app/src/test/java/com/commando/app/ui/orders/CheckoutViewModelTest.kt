package com.commando.app.ui.orders

// AI assistance: Claude Code (#419).
import com.commando.app.data.repository.CheckoutPaymentRepository
import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.PaymentError
import com.commando.app.data.repository.PaymentResult
import com.commando.app.model.*
import com.commando.app.ui.orders.CheckoutFixture.order
import com.commando.app.ui.orders.CheckoutFixture.payment
import com.commando.app.ui.orders.CheckoutFixture.trip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckoutViewModelTest {
  private var user: String? = order.requesterId
  private val sessions = MutableStateFlow<String?>(order.requesterId)

  @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

  @After fun tearDown() = Dispatchers.resetMain()

  private fun payments(initial: Payment? = null, stored: Order = order) =
      FakeCheckoutPayments(stored, trip.ownerId, { user }, initial)

  private fun viewModel(payments: CheckoutPaymentRepository, stored: Order = order) =
      CheckoutViewModel(
          order.id,
          FakeOrderRepository({ user }, listOf(stored)),
          FakeTripRepository(trip.ownerId, initialTrips = listOf(trip)),
          { payments },
          { user },
          sessions,
      )

  private fun CheckoutViewModel.content() = uiState.value as CheckoutUiState.Content

  @Test
  fun loadsPersistedTotalAndDefaultsToTwint() = runTest {
    val vm = viewModel(payments())
    assertEquals(CheckoutUiState.Loading, vm.uiState.value)
    advanceUntilIdle()
    val content = vm.content()
    assertEquals(840L, content.order.totalCents)
    assertEquals(null, content.payment)
    assertEquals(PaymentMethod.TWINT, content.method)
    assertTrue(content.canPay)
  }

  @Test
  fun selectedCardIsSubmittedOnceAndConfirmedPaymentIsReadOnly() = runTest {
    val payments = payments()
    val gate = CompletableDeferred<Unit>()
    payments.gate = gate
    val vm = viewModel(payments)
    advanceUntilIdle()
    vm.selectMethod(PaymentMethod.CARD)
    vm.pay()
    runCurrent()
    assertTrue(vm.content().processing)
    vm.pay()
    vm.selectMethod(PaymentMethod.TWINT)
    vm.refresh()
    runCurrent()
    assertEquals(PaymentMethod.CARD, vm.content().method)
    gate.complete(Unit)
    advanceUntilIdle()
    val confirmed = vm.content()
    assertEquals(PaymentStatus.CONFIRMED, confirmed.status)
    assertEquals(PaymentMethod.CARD, confirmed.payment?.method)
    assertFalse(confirmed.canPay)
    vm.pay()
    advanceUntilIdle()
    assertEquals(listOf(PaymentMethod.CARD), payments.calls)
  }

  @Test
  fun failedPaymentCanBeRetriedWithAnotherMethod() = runTest {
    val payments = payments()
    payments.outcome = { payments.stored(it, PaymentStatus.FAILED) }
    val vm = viewModel(payments)
    advanceUntilIdle()
    vm.pay()
    advanceUntilIdle()
    assertEquals(PaymentStatus.FAILED, vm.content().status)
    assertTrue(vm.content().canPay)
    payments.outcome = { payments.stored(it, PaymentStatus.CONFIRMED) }
    vm.selectMethod(PaymentMethod.CARD)
    vm.pay()
    advanceUntilIdle()
    assertEquals(PaymentStatus.CONFIRMED, vm.content().status)
    assertEquals(listOf(PaymentMethod.TWINT, PaymentMethod.CARD), payments.calls)
  }

  @Test
  fun pendingAndUncertainRetriesKeepTheStoredMethod() = runTest {
    for (status in listOf(PaymentStatus.PENDING, PaymentStatus.UNCERTAIN)) {
      val payments = payments(payment(status, PaymentMethod.CARD))
      val vm = viewModel(payments)
      advanceUntilIdle()
      assertTrue(vm.content().methodLocked)
      vm.selectMethod(PaymentMethod.TWINT)
      assertEquals(PaymentMethod.CARD, vm.content().method)
      vm.pay()
      advanceUntilIdle()
      assertEquals(listOf(PaymentMethod.CARD), payments.calls)
      assertEquals(PaymentStatus.CONFIRMED, vm.content().status)
    }
  }

  @Test
  fun uncertainResultIsNeverShownAsConfirmed() = runTest {
    val payments = payments()
    payments.outcome = { payments.stored(it, PaymentStatus.UNCERTAIN) }
    val vm = viewModel(payments)
    advanceUntilIdle()
    vm.pay()
    advanceUntilIdle()
    assertEquals(PaymentStatus.UNCERTAIN, vm.content().status)
    assertTrue(vm.content().canPay)
  }

  @Test
  fun lostResponseRequiresStatusReconciliationBeforeAnotherAttempt() = runTest {
    val payments = payments()
    payments.outcome = { throw IllegalStateException("offline") }
    val vm = viewModel(payments)
    advanceUntilIdle()
    vm.pay()
    advanceUntilIdle()
    assertEquals(PaymentError.UNKNOWN, vm.content().error)
    assertTrue(vm.content().needsReconciliation)
    assertFalse(vm.content().canPay)
    vm.pay()
    advanceUntilIdle()
    assertEquals(1, payments.calls.size)
    vm.refresh()
    advanceUntilIdle()
    assertEquals(null, vm.content().error)
    assertTrue(vm.content().canPay)
  }

  @Test
  fun invalidDataRejectionKeepsContentWithTheError() = runTest {
    val payments = payments()
    payments.outcome = { PaymentResult.Error(PaymentError.INVALID_DATA) }
    val vm = viewModel(payments)
    advanceUntilIdle()
    vm.pay()
    advanceUntilIdle()
    assertEquals(PaymentError.INVALID_DATA, vm.content().error)
    assertEquals(null, vm.content().payment)
  }

  @Test
  fun ineligibleRejectionReloadsTheStoredOrderInsteadOfOfferingRetry() = runTest {
    var stored = order
    val orders =
        object : OrderRepository by FakeOrderRepository({ user }, listOf(order)) {
          override suspend fun getOrderById(orderId: String) = OrderResult.Success(stored)
        }
    val payments = payments()
    payments.outcome = {
      stored = order.copy(status = OrderStatus.CANCELLED)
      PaymentResult.Error(PaymentError.INELIGIBLE)
    }
    val vm =
        CheckoutViewModel(
            order.id,
            orders,
            FakeTripRepository(trip.ownerId, initialTrips = listOf(trip)),
            { payments },
            { user },
        )
    advanceUntilIdle()
    vm.pay()
    advanceUntilIdle()
    assertEquals(CheckoutUiState.Ineligible, vm.uiState.value)
  }

  @Test
  fun accountChangeDuringProcessingDiscardsTheLateResult() = runTest {
    val payments = payments()
    val gate = CompletableDeferred<Unit>()
    payments.gate = gate
    val vm = viewModel(payments)
    advanceUntilIdle()
    vm.pay()
    runCurrent()
    user = "someone-else"
    sessions.value = user
    runCurrent()
    assertEquals(CheckoutUiState.SessionEnded, vm.uiState.value)
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals(CheckoutUiState.SessionEnded, vm.uiState.value)
    user = order.requesterId
    vm.refresh()
    vm.pay()
    advanceUntilIdle()
    assertEquals(CheckoutUiState.SessionEnded, vm.uiState.value)
  }

  @Test
  fun notAuthenticatedPaymentEndsTheSession() = runTest {
    val payments = payments()
    payments.outcome = { PaymentResult.Error(PaymentError.NOT_AUTHENTICATED) }
    val vm = viewModel(payments)
    advanceUntilIdle()
    vm.pay()
    advanceUntilIdle()
    assertEquals(CheckoutUiState.SessionEnded, vm.uiState.value)
  }

  @Test
  fun onlyTheRequesterOfAnAcceptedOrderCanPay() = runTest {
    for (status in listOf(OrderStatus.PENDING, OrderStatus.CANCELLED, OrderStatus.DELIVERED)) {
      val stored = order.copy(status = status)
      val vm = viewModel(payments(stored = stored), stored)
      advanceUntilIdle()
      assertEquals(status.name, CheckoutUiState.Ineligible, vm.uiState.value)
    }
    user = trip.ownerId
    sessions.value = user
    val owner = viewModel(payments())
    advanceUntilIdle()
    assertTrue(owner.uiState.value !is CheckoutUiState.Content)
  }

  @Test
  fun confirmedPaymentStaysViewableAfterTheOrderMovesOn() = runTest {
    val ready = order.copy(status = OrderStatus.READY_FOR_HANDOFF)
    val vm = viewModel(payments(payment(PaymentStatus.CONFIRMED), ready), ready)
    advanceUntilIdle()
    assertEquals(PaymentStatus.CONFIRMED, vm.content().status)
    assertFalse(vm.content().canPay)
  }

  @Test
  fun readFailuresOfferRetryAndMissingOrdersAreNotFound() = runTest {
    val payments = payments()
    payments.readFailure = IllegalStateException("offline")
    val vm = viewModel(payments)
    advanceUntilIdle()
    assertEquals(CheckoutUiState.LoadFailed, vm.uiState.value)
    payments.readFailure = null
    vm.refresh()
    advanceUntilIdle()
    assertTrue(vm.uiState.value is CheckoutUiState.Content)

    val missing =
        CheckoutViewModel(
            "missing",
            FakeOrderRepository({ user }),
            FakeTripRepository(trip.ownerId, initialTrips = listOf(trip)),
            { payments },
            { user },
        )
    advanceUntilIdle()
    assertEquals(CheckoutUiState.NotFound, missing.uiState.value)
  }
}
