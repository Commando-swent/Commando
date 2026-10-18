package com.android.sample.model

import java.time.Instant

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
