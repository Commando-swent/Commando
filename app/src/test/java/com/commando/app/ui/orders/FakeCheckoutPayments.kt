package com.commando.app.ui.orders

// AI assistance: Claude Code (#419).
import com.commando.app.data.repository.CheckoutPaymentRepository
import com.commando.app.data.repository.FakePaymentRepository
import com.commando.app.data.repository.PaymentRepository
import com.commando.app.data.repository.PaymentResult
import com.commando.app.model.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred

/** One requester's accepted order: 3 × CHF 2.30 + CHF 1.50 commission = CHF 8.40. */
internal object CheckoutFixture {
  private val time = Instant.parse("2026-11-20T12:00:00Z")
  val trip =
      Trip(
          "trip",
          "commando",
          Location("Migros EPFL", 46.52, 6.57),
          time,
          Location("Rolex Learning Center", 46.52, 6.57),
          TripStatus.PUBLISHED,
          time,
          time,
      )
  val order =
      Order(
          "order",
          trip.id,
          "requester",
          listOf(OrderItem("milk", "Milk", 230, 3)),
          150,
          OrderStatus.ACCEPTED,
          time,
      )

  fun payment(status: PaymentStatus, method: PaymentMethod = PaymentMethod.TWINT) =
      Payment(order.id, order.id, order.requesterId, order.totalCents, method, status)
}

/**
 * Checkout test double: reads, refunds and validation come from the shared [FakePaymentRepository];
 * `processPayment` (#418, not yet on this branch) records calls, may be [gate]d, and stores the
 * [outcome] so later reads observe it. Like #418, a stored CONFIRMED payment is never reprocessed.
 */
internal class FakeCheckoutPayments(
    private val order: Order,
    private val tripOwner: String,
    private val currentUserId: () -> String?,
    initial: Payment? = null,
) : CheckoutPaymentRepository {
  var outcome: (PaymentMethod) -> PaymentResult<Payment> = { stored(it, PaymentStatus.CONFIRMED) }
  var gate: CompletableDeferred<Unit>? = null
  var readFailure: Exception? = null
  val calls = mutableListOf<PaymentMethod>()
  private var payment = initial
  private var shared = rebuild()

  private fun rebuild(): PaymentRepository =
      FakePaymentRepository(
          currentUserId,
          listOf(order),
          mapOf(order.tripId to tripOwner),
          listOfNotNull(payment),
      )

  fun stored(method: PaymentMethod, status: PaymentStatus): PaymentResult<Payment> =
      PaymentResult.Success(
          Payment(order.id, order.id, order.requesterId, order.totalCents, method, status)
      )

  override suspend fun processPayment(
      orderId: String,
      method: PaymentMethod,
  ): PaymentResult<Payment> {
    calls += method
    gate?.await()
    payment
        ?.takeIf { it.status == PaymentStatus.CONFIRMED }
        ?.let {
          return PaymentResult.Success(it)
        }
    return outcome(method).also {
      if (it is PaymentResult.Success) {
        payment = it.data
        shared = rebuild()
      }
    }
  }

  override suspend fun getPayment(orderId: String): PaymentResult<Payment?> {
    readFailure?.let { throw it }
    return shared.getPayment(orderId)
  }

  override suspend fun getRefund(orderId: String) = shared.getRefund(orderId)

  override suspend fun refundForCancellation(orderId: String) =
      shared.refundForCancellation(orderId)
}
