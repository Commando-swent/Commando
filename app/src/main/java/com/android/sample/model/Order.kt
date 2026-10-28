package com.android.sample.model

import java.time.Instant

/** Maximum distinct products per order; mirrored by the production Firestore rules. */
const val MAX_ORDER_PRODUCTS = 5

/** Product snapshot; CHF prices use integer cents, never floating point. */
data class OrderItem(
    val productId: String,
    val name: String,
    val unitPriceCents: Int,
    val quantity: Int,
)

/** Shared Catalogue/Review payload. The flow must own a single instance of draft state. */
data class OrderDraft(
    val tripId: String,
    val items: List<OrderItem>,
    val commissionCents: Int = 0,
)

enum class OrderStatus {
  PENDING,
  ACCEPTED,
  REJECTED,
}

enum class OrderDecision {
  ACCEPT,
  REJECT,
}

/** Immutable order snapshot; product prices do not track later catalogue changes. */
data class Order(
    val id: String,
    val tripId: String,
    val requesterId: String,
    val items: List<OrderItem>,
    val commissionCents: Int,
    val status: OrderStatus,
    val createdAt: Instant,
)

/** Total quantity across products, e.g. two apples and one milk count as three items. */
val Order.itemCount: Long
  get() = items.sumOf { it.quantity.toLong() }

/** Products plus commission, in CHF cents; Long, so the sum cannot overflow. */
val Order.totalCents: Long
  get() = items.sumOf { it.unitPriceCents.toLong() * it.quantity } + commissionCents
