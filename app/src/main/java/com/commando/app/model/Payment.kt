package com.commando.app.model

/** Simulated methods only; no card number, bank account or real payment credential is stored. */
enum class PaymentMethod {
  TWINT,
  CARD,
}

/** Confirmation is distinct from an unresolved payment response. */
enum class PaymentStatus {
  PENDING,
  CONFIRMED,
  FAILED,
  UNCERTAIN,
}

/** One captured-payment operation per order; CHF amounts come from the stored order snapshot. */
data class Payment(
    val id: String,
    val orderId: String,
    val requesterId: String,
    val amountCents: Long,
    val method: PaymentMethod,
    val status: PaymentStatus,
)

/** Only CONFIRMED authorizes final cancellation of a paid order. */
enum class RefundStatus {
  PENDING,
  CONFIRMED,
  FAILED,
  UNCERTAIN,
}

/** Immutable refund identity and amount; retries change the outcome of the same operation. */
data class Refund(
    val operationId: String,
    val orderId: String,
    val paymentId: String,
    val requesterId: String,
    val requestedBy: String,
    val amountCents: Long,
    val status: RefundStatus,
)
