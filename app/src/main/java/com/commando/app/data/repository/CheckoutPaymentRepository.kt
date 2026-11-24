package com.commando.app.data.repository

// AI assistance: Claude Code (#419).
import com.commando.app.model.Payment
import com.commando.app.model.PaymentMethod

/**
 * Checkout's view of the shared payment boundary.
 *
 * shortcut: mirrors the `processPayment` signature proposed by the backend branch (#418); once #418
 * adds it to [PaymentRepository], delete this interface and depend on [PaymentRepository]. Until
 * then no production repository implements it, so MainActivity passes no factory and checkout is
 * unreachable outside tests.
 */
interface CheckoutPaymentRepository : PaymentRepository {
  /** Begins or retries the requester's payment; every retry reuses the order's operation ID. */
  suspend fun processPayment(orderId: String, method: PaymentMethod): PaymentResult<Payment>
}
