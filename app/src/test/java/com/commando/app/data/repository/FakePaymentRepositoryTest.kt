package com.commando.app.data.repository

import com.commando.app.model.*
import com.commando.app.testing.PaymentFixtures
import com.commando.app.testing.PaymentFixtures.OWNER
import com.commando.app.testing.PaymentFixtures.refund as expectedRefund
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Refund authorization, persistence semantics and retry safety shared by both adapters. */
class FakePaymentRepositoryTest {
  private val order = PaymentFixtures.order
  private val payment = PaymentFixtures.captured
  private val context = PaymentContext(order, OWNER, payment, null)
  private val owners = mapOf(order.tripId to OWNER)

  private fun source() = FakePaymentDataSource(listOf(order), owners, listOf(payment))

  private var uid: String? = order.requesterId

  private fun repository(
      storedOrder: Order? = order,
      storedPayment: Payment? = payment,
      owner: String? = OWNER,
      refund: Refund? = null,
      simulate: (Refund) -> RefundStatus = { RefundStatus.CONFIRMED },
  ) =
      FakePaymentRepository(
          { uid },
          listOfNotNull(storedOrder),
          owner?.let { mapOf(order.tripId to it) }.orEmpty(),
          listOfNotNull(storedPayment),
          listOfNotNull(refund),
          simulate,
      )

  @Test
  fun authorizedReadsPreserveAnAbsentPaymentAndRefund() = runTest {
    val repo = FakePaymentRepository({ uid }, listOf(order), owners)
    repo.getPayment(order.id).assertData(null)
    repo.getRefund(order.id).assertData(null)
    repo.refundForCancellation(order.id).assertError(PaymentError.NOT_CAPTURED)
  }

  @Test
  fun pendingRequesterRefundUsesTheCapturedAmountAndStableIdentity() = runTest {
    val repo = FakePaymentRepository({ uid }, listOf(order), owners, listOf(payment))
    repo.refundForCancellation(order.id).assertData(expectedRefund())
    repo.getRefund(order.id).assertData(expectedRefund())
    repo.getPayment(order.id).assertData(payment)
  }

  @Test
  fun acceptedOrderRefundRequiresItsCommando() = runTest {
    uid = OWNER
    val repo = repository(storedOrder = order.copy(status = OrderStatus.ACCEPTED))
    repo.refundForCancellation(order.id).assertData(expectedRefund().copy(requestedBy = uid!!))
  }

  @Test
  fun simultaneousRequestsCannotCreateDuplicateRefunds() = runTest {
    val calls = AtomicInteger()
    val repo = repository {
      calls.incrementAndGet()
      RefundStatus.CONFIRMED
    }
    val results =
        List(20) { async(Dispatchers.Default) { repo.refundForCancellation(order.id) } }.awaitAll()
    assertEquals(listOf(PaymentResult.Success(expectedRefund())), results.distinct())
    assertEquals(1, calls.get())
  }

  @Test
  fun unfinishedOutcomesRemainStoredAndRetryTheSameOperation() = runTest {
    for (status in listOf(RefundStatus.PENDING, RefundStatus.FAILED, RefundStatus.UNCERTAIN)) {
      var next = status
      val repo = repository { next }
      val first = expectedRefund(status)
      repo.refundForCancellation(order.id).assertData(first)
      repo.getRefund(order.id).assertData(first)
      next = RefundStatus.CONFIRMED
      repo.refundForCancellation(order.id).assertData(first.copy(status = next))
    }
  }

  @Test
  fun confirmedRefundReloadIsReadOnlyAfterCancellation() = runTest {
    val repo =
        repository(
            storedOrder = order.copy(status = OrderStatus.CANCELLED),
            refund = expectedRefund(),
        ) {
          error("Confirmed refunds must not be processed again")
        }
    repo.refundForCancellation(order.id).assertData(expectedRefund())
  }

  @Test
  fun outsidersCannotDiscoverWhetherPaymentExists() = runTest {
    uid = "outsider"
    for (stored in listOf(null, payment)) {
      val repo = repository(storedPayment = stored)
      repo.getPayment(order.id).assertError(PaymentError.PERMISSION_DENIED)
      repo.getRefund(order.id).assertError(PaymentError.PERMISSION_DENIED)
      repo.refundForCancellation(order.id).assertError(PaymentError.PERMISSION_DENIED)
    }
  }

  @Test
  fun endedSessionsCannotReadOrRefund() = runTest {
    for (identity in listOf(null, " ")) {
      uid = identity
      val repo = repository()
      repo.getPayment(order.id).assertError(PaymentError.NOT_AUTHENTICATED)
      repo.getRefund(order.id).assertError(PaymentError.NOT_AUTHENTICATED)
      repo.refundForCancellation(order.id).assertError(PaymentError.NOT_AUTHENTICATED)
    }
  }

  @Test
  fun pathsAndEmptyIdsAreNotDocumentIds() = runTest {
    for (id in listOf("", " ", ".", "..", "orders/order")) repository()
        .refundForCancellation(id)
        .assertError(PaymentError.INVALID_DATA)
  }

  @Test
  fun missingOrderIsNotAnUnpaidOrder() = runTest {
    repository(storedOrder = null).getPayment(order.id).assertError(PaymentError.NOT_FOUND)
  }

  @Test
  fun missingTripCannotAuthorizePaymentAccess() = runTest {
    repository(owner = null).getRefund(order.id).assertError(PaymentError.NOT_FOUND)
  }

  @Test
  fun onlyConfirmedCapturedPaymentsAreRefundable() = runTest {
    for (status in listOf(PaymentStatus.PENDING, PaymentStatus.FAILED, PaymentStatus.UNCERTAIN)) {
      val stored = payment.copy(status = status)
      val repo = repository(storedPayment = stored)
      repo.getPayment(order.id).assertData(stored)
      repo.refundForCancellation(order.id).assertError(PaymentError.NOT_CAPTURED)
    }
  }

  @Test
  fun otherLifecycleStatesAndWrongCancellationActorsAreRejected() = runTest {
    for ((status, actor) in
        listOf(
            OrderStatus.PENDING to OWNER,
            OrderStatus.ACCEPTED to order.requesterId,
            OrderStatus.READY_FOR_HANDOFF to OWNER,
            OrderStatus.DELIVERED to order.requesterId,
            OrderStatus.REJECTED to OWNER,
            OrderStatus.CANCELLED to order.requesterId,
        )) {
      uid = actor
      repository(storedOrder = order.copy(status = status))
          .refundForCancellation(order.id)
          .assertError(PaymentError.INELIGIBLE)
    }
  }

  @Test
  fun malformedCapturedRecordsCannotAuthorizeARefund() = runTest {
    for (bad in
        listOf(
            payment.copy(id = "wrong"),
            payment.copy(orderId = "other"),
            payment.copy(requesterId = "other"),
            payment.copy(amountCents = 0),
            payment.copy(amountCents = payment.amountCents + 1),
        )) untrusted(context.copy(payment = bad))
        .refundForCancellation(order.id)
        .assertError(PaymentError.INVALID_DATA)
  }

  @Test
  fun invalidOrderRelationshipsAndCapturedTotalsAreRejectedBeforeWrites() = runTest {
    for (bad in
        listOf(
            context.copy(order = order.copy(id = "other")),
            context.copy(order = order.copy(tripId = "")),
            context.copy(tripOwnerId = ""),
            context.copy(tripOwnerId = uid),
            context.copy(order = order.copy(items = emptyList())),
            context.copy(order = order.copy(commissionCents = order.commissionCents + 1)),
        )) untrusted(bad).refundForCancellation(order.id).assertError(PaymentError.INVALID_DATA)
  }

  @Test
  fun forgedRefundIdentityAndAmountsAreRejected() = runTest {
    val refund = expectedRefund()
    for (bad in
        listOf(
            refund.copy(operationId = "other"),
            refund.copy(orderId = "other"),
            refund.copy(paymentId = "other"),
            refund.copy(requesterId = "other"),
            refund.copy(amountCents = 1),
            refund.copy(requestedBy = "outsider"),
        )) untrusted(context.copy(refund = bad))
        .getRefund(order.id)
        .assertError(PaymentError.INVALID_DATA)
    untrusted(context.copy(payment = null, refund = refund))
        .getRefund(order.id)
        .assertError(PaymentError.INVALID_DATA)
  }

  @Test
  fun anotherParticipantCannotTakeOverAnExistingRefund() = runTest {
    uid = OWNER
    val repo =
        repository(
            storedOrder = order.copy(status = OrderStatus.ACCEPTED),
            refund = expectedRefund(RefundStatus.PENDING),
        )
    repo.refundForCancellation(order.id).assertError(PaymentError.PERMISSION_DENIED)
  }

  @Test
  fun unexpectedFailureDoesNotPublishPartialRefunds() = runTest {
    val repo = repository { throw IOException("Transport failed") }
    repo.refundForCancellation(order.id).assertError(PaymentError.UNKNOWN)
    repo.getRefund(order.id).assertData(null)
  }

  @Test
  fun coroutineCancellationIsPropagatedWithoutPublishingARefund() = runTest {
    val repo = repository { throw CancellationException("Cancelled") }
    try {
      repo.refundForCancellation(order.id)
      fail("Cancellation must propagate")
    } catch (expected: CancellationException) {
      assertEquals("Cancelled", expected.message)
    }
    repo.getRefund(order.id).assertData(null)
  }

  @Test
  fun alreadyCancelledCallsPropagateBeforeValidation() = runTest {
    val repo = repository()
    val calls = listOf(repo::getPayment, repo::getRefund, repo::refundForCancellation)
    for (call in calls) for (identity in listOf(null, order.requesterId)) {
      uid = identity
      launch(start = CoroutineStart.UNDISPATCHED) {
        cancel()
        assertTrue(runCatching { call("") }.exceptionOrNull() is CancellationException)
      }
    }
  }

  @Test
  fun sessionChangeBeforeCommitRollsBackTheRefund() = runTest {
    val source = source()
    val repo =
        TransactionalPaymentRepository(source, { uid }) {
          uid = null
          RefundStatus.CONFIRMED
        }
    repo.refundForCancellation(order.id).assertError(PaymentError.NOT_AUTHENTICATED)
    val nextSession = TransactionalPaymentRepository(source, { order.requesterId })
    nextSession.getRefund(order.id).assertData(null)
    nextSession.refundForCancellation(order.id).assertData(expectedRefund())
  }

  @Test
  fun uncertainCommitIsReconciledWithoutAnotherRefund() = runTest {
    val source = source()
    var loseResponse = true
    val transport =
        object : PaymentDataSource {
          override suspend fun <T> transaction(action: (PaymentTransaction) -> T): T {
            val result = source.transaction(action)
            if (loseResponse) {
              loseResponse = false
              throw IOException("Response lost after commit")
            }
            return result
          }
        }
    var calls = 0
    val repo =
        TransactionalPaymentRepository(transport, { uid }) {
          calls++
          RefundStatus.CONFIRMED
        }
    repo.refundForCancellation(order.id).assertError(PaymentError.UNKNOWN)
    repo.refundForCancellation(order.id).assertData(expectedRefund())
    assertEquals(1, calls)
  }

  private fun PaymentResult<*>.assertError(expected: PaymentError) =
      assertEquals(PaymentResult.Error(expected), this)

  private fun PaymentResult<*>.assertData(expected: Any?) =
      assertEquals(PaymentResult.Success(expected), this)

  private fun untrusted(context: PaymentContext): PaymentRepository =
      TransactionalPaymentRepository(UntrustedContext(context), { uid })

  /** Any attempted mutation makes a malformed-data test fail. */
  private class UntrustedContext(private val context: PaymentContext) :
      PaymentDataSource, PaymentTransaction {
    override suspend fun <T> transaction(action: (PaymentTransaction) -> T): T = action(this)

    override fun read(orderId: String) = context

    override fun save(refund: Refund) = error("Malformed data must not be written")
  }
}
