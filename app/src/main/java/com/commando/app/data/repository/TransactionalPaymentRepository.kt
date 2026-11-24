package com.commando.app.data.repository

import com.commando.app.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** All relationships are read in the same backend transaction before any refund write. */
internal data class PaymentContext(
    val order: Order?,
    val tripOwnerId: String?,
    val payment: Payment?,
    val refund: Refund?,
)

/** Authoritative read set and staged refund write within one transaction. */
internal interface PaymentTransaction {
  fun read(orderId: String): PaymentContext

  fun save(refund: Refund)
}

/** A callback may run again on conflict; failed callbacks must not publish staged writes. */
internal interface PaymentDataSource {
  suspend fun <T> transaction(action: (PaymentTransaction) -> T): T
}

/** Domain failure survives SDK transaction callbacks without being treated as a transport error. */
internal class PaymentFailure(val error: PaymentError) : RuntimeException()

internal fun requirePayment(condition: Boolean, error: PaymentError = PaymentError.INVALID_DATA) {
  if (!condition) throw PaymentFailure(error)
}

private fun Payment.refundOperationId() = "refund-$id"

/** The simulation is pure: a retryable backend transaction must never issue an external payment. */
internal class TransactionalPaymentRepository(
    private val source: PaymentDataSource,
    private val currentUserId: () -> String?,
    private val simulate: (Refund) -> RefundStatus = { RefundStatus.CONFIRMED },
) : PaymentRepository {
  override suspend fun getPayment(orderId: String) =
      operation(orderId) { context, _ -> context.payment }

  override suspend fun getRefund(orderId: String) =
      operation(orderId) { context, _ -> context.refund }

  override suspend fun refundForCancellation(orderId: String) =
      operation(orderId) { context, uid ->
        val order = checkNotNull(context.order)
        val payment = context.payment ?: throw PaymentFailure(PaymentError.NOT_CAPTURED)
        requirePayment(payment.status == PaymentStatus.CONFIRMED, PaymentError.NOT_CAPTURED)
        val previous = context.refund
        requirePayment(
            previous == null || previous.requestedBy == uid,
            PaymentError.PERMISSION_DENIED,
        )
        if (previous?.status == RefundStatus.CONFIRMED) return@operation previous
        requirePayment(
            (order.status == OrderStatus.PENDING && uid == order.requesterId) ||
                (order.status == OrderStatus.ACCEPTED && uid == context.tripOwnerId),
            PaymentError.INELIGIBLE,
        )
        val refund =
            previous
                ?: Refund(
                    payment.refundOperationId(),
                    orderId,
                    payment.id,
                    order.requesterId,
                    uid,
                    payment.amountCents,
                    RefundStatus.PENDING,
                )
        refund.copy(status = simulate(refund)).also { save(it) }
      }

  private suspend fun <T> operation(
      orderId: String,
      action: PaymentTransaction.(PaymentContext, String) -> T,
  ): PaymentResult<T> {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    val uid =
        currentUserId()?.takeIf(String::isNotBlank)
            ?: return PaymentResult.Error(PaymentError.NOT_AUTHENTICATED)
    if (!orderId.isDocumentId()) return PaymentResult.Error(PaymentError.INVALID_DATA)
    fun guard() {
      caller.ensureActive()
      requirePayment(currentUserId() == uid, PaymentError.NOT_AUTHENTICATED)
    }
    return try {
      val value = source.transaction { transaction ->
        guard()
        val context = transaction.read(orderId)
        authorize(context, orderId, uid)
        transaction.action(context, uid).also { guard() }
      }
      guard()
      PaymentResult.Success(value)
    } catch (failure: Exception) {
      caller.ensureActive()
      if (failure is CancellationException) throw failure
      val error = if (failure is PaymentFailure) failure.error else PaymentError.UNKNOWN
      PaymentResult.Error(if (currentUserId() == uid) error else PaymentError.NOT_AUTHENTICATED)
    }
  }

  private fun authorize(context: PaymentContext, id: String, uid: String) {
    val order = context.order ?: throw PaymentFailure(PaymentError.NOT_FOUND)
    val owner = context.tripOwnerId
    requirePayment(uid == order.requesterId || uid == owner, PaymentError.PERMISSION_DENIED)
    requirePayment(order.id == id && order.tripId.isDocumentId() && order.requesterId.isNotBlank())
    if (owner == null) throw PaymentFailure(PaymentError.NOT_FOUND)
    requirePayment(owner.isNotBlank() && owner != order.requesterId)
    requirePayment(OrderDraft(order.tripId, order.items, order.commissionCents).isValid())
    val payment = context.payment
    if (payment != null)
        requirePayment(
            payment.id == id &&
                payment.orderId == id &&
                payment.requesterId == order.requesterId &&
                payment.amountCents > 0 &&
                payment.amountCents == order.totalCents,
        )
    val refund = context.refund
    if (refund != null)
        requirePayment(
            payment != null &&
                order.status in
                    listOf(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.CANCELLED) &&
                (order.status != OrderStatus.CANCELLED ||
                    refund.status == RefundStatus.CONFIRMED) &&
                refund.orderId == id &&
                refund.paymentId == payment.id &&
                payment.status == PaymentStatus.CONFIRMED &&
                refund.operationId == payment.refundOperationId() &&
                refund.requesterId == order.requesterId &&
                refund.amountCents == payment.amountCents &&
                refund.requestedBy in listOf(order.requesterId, owner),
        )
  }
}
