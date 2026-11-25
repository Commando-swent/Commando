package com.commando.app.testing

import com.commando.app.model.*
import java.time.Instant

/** Shared persisted snapshots for refund policy, adapter and screen regressions. */
internal object PaymentFixtures {
  const val OWNER = "commando"
  val order =
      Order(
          "order",
          "trip",
          "requester",
          listOf(OrderItem("milk", "Milk", 125, 2)),
          75,
          OrderStatus.PENDING,
          Instant.EPOCH,
      )
  val captured =
      Payment(
          order.id,
          order.id,
          order.requesterId,
          order.totalCents,
          PaymentMethod.TWINT,
          PaymentStatus.CONFIRMED,
      )

  fun refund(status: RefundStatus = RefundStatus.CONFIRMED) =
      Refund(
          "refund-${captured.id}",
          order.id,
          captured.id,
          order.requesterId,
          order.requesterId,
          captured.amountCents,
          status,
      )
}
