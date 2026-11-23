package com.commando.app.data.repository

import com.commando.app.model.Payment
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
 * Shared payment boundary for checkout and cancellation. Implementations derive identity, amount
 * and eligibility from storage. The identity callback must be bound to one session and become null
 * permanently when that session ends. Cancellation is rethrown; it cannot undo a server commit.
 */
interface PaymentRepository {
  /** Reads the stored payment, or null, for the order's requester or its trip owner. */
  suspend fun getPayment(orderId: String): PaymentResult<Payment?>

  /** Reads the same refund operation after reload, including an uncertain write response. */
  suspend fun getRefund(orderId: String): PaymentResult<Refund?>

  /**
   * Begins/retries a cancellation refund: PENDING requester or ACCEPTED trip owner only. IDs and
   * amounts are never accepted from the caller. Confirmed retries are read-only; all other outcomes
   * reuse the original operation. Creating a refund reserves the order for cancellation; the
   * cancellation workflow must not finalize it until this outcome is CONFIRMED.
   *
   * @param orderId Stored order document ID, never a document path.
   */
  suspend fun refundForCancellation(orderId: String): PaymentResult<Refund>
}
