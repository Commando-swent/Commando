package com.commando.app.data.repository

import com.commando.app.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Mutex-serialized fixture backend; [simulateRefund] is pure. No Firebase security guarantees. */
class FakePaymentRepository(
    currentUserId: () -> String?,
    initialOrders: List<Order>,
    tripOwners: Map<String, String>,
    initialPayments: List<Payment> = emptyList(),
    initialRefunds: List<Refund> = emptyList(),
    simulateRefund: (Refund) -> RefundStatus = { RefundStatus.CONFIRMED },
) :
    PaymentRepository by TransactionalPaymentRepository(
        FakePaymentDataSource(initialOrders, tripOwners, initialPayments, initialRefunds),
        currentUserId,
        simulateRefund,
    )

/** Commits only after the complete callback, so exceptions and ended sessions roll back writes. */
internal class FakePaymentDataSource(
    orders: List<Order>,
    owners: Map<String, String>,
    payments: List<Payment>,
    refunds: List<Refund> = emptyList(),
) : PaymentDataSource {
  private val orders = orders.map { it.copy(items = it.items.toList()) }.associateBy(Order::id)
  private val owners = owners.toMap()
  private val payments = payments.associateBy(Payment::orderId)
  private val refunds = refunds.associateBy(Refund::orderId).toMutableMap()
  private val mutex = Mutex()

  override suspend fun <T> transaction(action: (PaymentTransaction) -> T): T = mutex.withLock {
    val pending = mutableMapOf<String, Refund>()
    val result =
        action(
            object : PaymentTransaction {
              override fun read(orderId: String): PaymentContext {
                val order = orders[orderId]
                return PaymentContext(
                    order,
                    owners[order?.tripId],
                    payments[orderId],
                    refunds[orderId],
                )
              }

              override fun save(refund: Refund) {
                pending[refund.orderId] = refund
              }
            }
        )
    refunds.putAll(pending)
    result
  }
}
