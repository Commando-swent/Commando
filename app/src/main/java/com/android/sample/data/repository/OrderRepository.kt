package com.android.sample.data.repository

import com.android.sample.model.Order
import com.android.sample.model.OrderDecision
import com.android.sample.model.OrderDraft
import com.android.sample.model.OrderStatus

/** Inject the SAME session-scoped repository into Review, My Orders and Order Details. */
interface OrderRepository {
  /** Derives requester identity from the session; does not clear the caller's draft. */
  suspend fun createOrder(draft: OrderDraft): OrderResult<Order>

  /** Current requester's orders, newest first; equal timestamps are ordered by ID. */
  suspend fun getMyOrders(): OrderResult<List<Order>>

  /**
   * Allows the requester or authenticated owner of the associated Trip; unrelated users are denied.
   */
  suspend fun getOrderById(orderId: String): OrderResult<Order>

  /**
   * Trip owner only; identical final decisions are read-only, opposite ones return AlreadyDecided.
   */
  suspend fun decideOrder(orderId: String, decision: OrderDecision): OrderResult<Order>
}

internal fun OrderDecision.targetStatus(): OrderStatus =
    when (this) {
      OrderDecision.ACCEPT -> OrderStatus.ACCEPTED
      OrderDecision.REJECT -> OrderStatus.REJECTED
    }
