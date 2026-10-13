package com.android.sample.data.repository

import com.android.sample.model.Order
import com.android.sample.model.OrderDraft

/** Inject the SAME in-memory fake into Review, My Orders and Order Details. */
interface OrderRepository {
  /** Derives requester identity from the session; does not clear the caller's draft. */
  suspend fun createOrder(draft: OrderDraft): OrderResult<Order>

  /** Current requester's orders, newest first; equal timestamps are ordered by ID. */
  suspend fun getMyOrders(): OrderResult<List<Order>>

  /** Requester-only for this slice; no trip-owner access is inferred from an ID. */
  suspend fun getOrderById(orderId: String): OrderResult<Order>
}
