package com.commando.app.data.repository

import com.commando.app.model.Payment
import com.commando.app.model.PaymentMethod
import com.commando.app.model.Refund

/** Payment/refund failures; they never expose another account's stored payload. */
enum class PaymentError {
  NOT_AUTHENTICATED,
  PERMISSION_DENIED,
  NOT_FOUND,
  INVALID_DATA,
  NOT_CAPTURED,
  INELIGIBLE,
  UNKNOWN,
}

/** Shared payment outcome, including authorized reads without a payment/refund. */
sealed interface PaymentResult<out T> {
  /** Confirmed repository data; nullable reads preserve a missing record. */
  data class Success<T>(val data: T) : PaymentResult<T>

  /** No successful payload is available to the caller. */
  data class Error(val error: PaymentError) : PaymentResult<Nothing>
}

/**
 * Shared checkout/refund boundary: payment.id == payment.orderId == order.id (#385). Capture only
 * ACCEPTED orders using the stored total including commission; the captured total and order
 * prices/commission are immutable afterwards. Reconcile UNCERTAIN before retry. Implementations
 * derive identity/eligibility from storage; the session callback becomes null permanently on
 * account change. Cancellation propagates but cannot undo a server commit.
 */
interface PaymentRepository {
  /** Reads the stored payment, or null, for the order's requester or its trip owner. */
  suspend fun getPayment(orderId: String): PaymentResult<Payment?>

  /**
   * Begins or retries the requester's simulated payment for an ACCEPTED order. Identity, amount and
   * eligibility come from storage; retries reuse the order ID and never reprocess a confirmed,
   * pending or uncertain payment.
   */
  suspend fun processPayment(orderId: String, method: PaymentMethod): PaymentResult<Payment>

  /** Reads the same refund operation after reload, including an uncertain write response. */
  suspend fun getRefund(orderId: String): PaymentResult<Refund?>

  /**
   * Begins/retries a cancellation refund: PENDING requester or ACCEPTED trip owner only. IDs and
   * amounts are never accepted from the caller. Confirmed retries are read-only; all other outcomes
   * reuse the original operation. This repository records refund state only; #406 persistence
   * blocks conflicting order changes, and #407 finalizes cancellation only after CONFIRMED.
   *
   * @param orderId Stored order document ID, never a document path.
   */
  suspend fun refundForCancellation(orderId: String): PaymentResult<Refund>
}
