package com.android.sample.data.repository

import com.android.sample.model.Order
import com.android.sample.model.OrderDraft

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
   * Orders of [tripId] for the authenticated owner of that Trip, newest first; equal timestamps are
   * ordered by ID. Requesters and unrelated users are denied, even for orders they placed.
   */
  suspend fun getOrdersForTrip(tripId: String): OrderResult<List<Order>>
}

/** Shared list order of every order query. */
internal val NEWEST_ORDER_FIRST: Comparator<Order> =
    compareByDescending<Order> { it.createdAt }.thenBy { it.id }
