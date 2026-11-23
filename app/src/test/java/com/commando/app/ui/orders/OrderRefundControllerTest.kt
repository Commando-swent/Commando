package com.commando.app.ui.orders

import com.commando.app.data.repository.*
import com.commando.app.model.*
import com.commando.app.testing.PaymentFixtures
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderRefundControllerTest {
  @Test
  fun onlyAuthoritativeUnpaidDataBypassesTheRefundAndUncertaintyFailsClosed() = runTest {
    for (status in PaymentStatus.entries + null) {
      val payments =
          Payments().apply {
            payment = {
              PaymentResult.Success(status?.let { PaymentFixtures.captured.copy(status = it) })
            }
          }
      val controller = OrderRefundController("order", payments, { "requester" }, this)
      val allowed = controller.beforeCancellation()
      assertEquals(status in listOf(null, PaymentStatus.FAILED, PaymentStatus.CONFIRMED), allowed)
      assertEquals(if (status == PaymentStatus.CONFIRMED) 1 else 0, payments.attempts)
    }
    for (error in PaymentError.entries) {
      val payments = Payments().apply { payment = { PaymentResult.Error(error) } }
      val controller = OrderRefundController("order", payments, { "requester" }, this)
      assertFalse(controller.beforeCancellation())
      assertEquals(RefundUiState.Unavailable(error), controller.state.value)
      assertEquals(0, payments.attempts)
    }
  }

  @Test
  fun processingPreventsDuplicateOperationsAndRefreshDoesNotSubmit() = runTest {
    val gate = CompletableDeferred<PaymentResult<Refund>>()
    val payments = Payments().apply { refund = { gate.await() } }
    val controller = OrderRefundController("order", payments, { "requester" }, this)
    val cancellation = async { controller.beforeCancellation() }
    runCurrent()
    assertTrue((controller.state.value as RefundUiState.Content).processing)
    controller.load()
    assertFalse(controller.beforeCancellation())
    assertEquals(1, payments.attempts)
    gate.complete(PaymentResult.Success(PaymentFixtures.refund(RefundStatus.UNCERTAIN)))
    assertFalse(cancellation.await())
    controller.load()
    advanceUntilIdle()
    assertFalse((controller.state.value as RefundUiState.Content).processing)
    assertEquals(1, payments.attempts)
  }

  @Test
  fun endedSessionRejectsLateResultsEvenWhenTheOriginalAccountReturns() = runTest {
    var uid = "requester"
    val gate = CompletableDeferred<PaymentResult<Payment?>>()
    val payments = Payments().apply { payment = { withContext(NonCancellable) { gate.await() } } }
    val controller = OrderRefundController("order", payments, { uid }, this)
    controller.load()
    runCurrent()
    uid = "other"
    controller.load()
    controller.clear()
    uid = "requester"
    gate.complete(PaymentResult.Success(PaymentFixtures.captured))
    advanceUntilIdle()
    controller.load()
    assertFalse(controller.beforeCancellation())
    assertEquals(RefundUiState.Hidden, controller.state.value)
    assertEquals(0, payments.attempts)
    assertFalse(OrderRefundController("order", payments, { null }, this).beforeCancellation())
  }

  @Test
  fun errorsRemainVisibleAndCancellationDoesNotLeaveProcessingStuck() = runTest {
    val payments = Payments().apply { refund = { throw CancellationException() } }
    val controller = OrderRefundController("order", payments, { "requester" }, this)
    try {
      controller.beforeCancellation()
      fail("Expected cancellation")
    } catch (_: CancellationException) {}
    assertFalse((controller.state.value as RefundUiState.Content).processing)
    payments.refund = { PaymentResult.Error(PaymentError.UNKNOWN) }
    assertFalse(controller.beforeCancellation())
    assertEquals(PaymentError.UNKNOWN, (controller.state.value as RefundUiState.Content).error)
    payments.existing = PaymentResult.Error(PaymentError.INVALID_DATA)
    assertFalse(controller.beforeCancellation())
    assertEquals(RefundUiState.Unavailable(PaymentError.INVALID_DATA), controller.state.value)
    payments.payment = { throw IOException() }
    controller.load()
    advanceUntilIdle()
    assertEquals(RefundUiState.Unavailable(PaymentError.UNKNOWN), controller.state.value)
  }

  private class Payments : PaymentRepository {
    var attempts = 0
    var existing: PaymentResult<Refund?> = PaymentResult.Success(null)
    var payment: suspend () -> PaymentResult<Payment?> = {
      PaymentResult.Success(PaymentFixtures.captured)
    }
    var refund: suspend () -> PaymentResult<Refund> = {
      PaymentResult.Success(PaymentFixtures.refund())
    }

    override suspend fun getPayment(orderId: String) = payment()

    override suspend fun getRefund(orderId: String) = existing

    override suspend fun refundForCancellation(orderId: String): PaymentResult<Refund> {
      attempts++
      return refund()
    }
  }
}
