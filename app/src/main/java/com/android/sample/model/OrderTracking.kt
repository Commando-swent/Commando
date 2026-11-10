package com.android.sample.model

// AI assistance: OpenAI Codex.
/** A requester tracks these orders until rejection, cancellation or confirmed delivery. */
fun OrderStatus.isActiveForTracking(): Boolean =
    when (this) {
      OrderStatus.PENDING,
      OrderStatus.ACCEPTED,
      OrderStatus.READY_FOR_HANDOFF -> true
      OrderStatus.REJECTED,
      OrderStatus.CANCELLED,
      OrderStatus.DELIVERED -> false
    }
