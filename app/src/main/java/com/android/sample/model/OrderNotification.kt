package com.android.sample.model

// AI assistance: OpenAI Codex.
import java.time.Instant

/** Initial in-app events; cancellation and handoff events are outside #190. */
enum class NotificationEventType {
  ORDER_PLACED,
  ORDER_ACCEPTED,
  ORDER_REJECTED;

  /** A stable document ID, reused by event publication and inbox processing. */
  fun eventId(orderId: String): String = "${orderId}_${name}"
}

/** A persisted order event, independent of presentation language and read state. */
data class OrderNotificationEvent(
    val orderId: String,
    val recipientId: String,
    val type: NotificationEventType,
    val occurredAt: Instant,
) {
  init {
    require(orderId.isNotBlank() && recipientId.isNotBlank())
  }

  /** Stable across retries; scoped to the recipient's inbox by the repository. */
  val id: String = type.eventId(orderId)
}

/** The recipient's read state for an immutable event. */
data class OrderNotification(val event: OrderNotificationEvent, val isRead: Boolean = false)

/** Both categories default to enabled; changes affect subsequently processed events only. */
data class NotificationPreferences(
    val newOrders: Boolean = true,
    val orderDecisions: Boolean = true,
) {
  /** Whether this category is enabled for a new event. */
  fun allows(type: NotificationEventType): Boolean =
      when (type) {
        NotificationEventType.ORDER_PLACED -> newOrders
        NotificationEventType.ORDER_ACCEPTED,
        NotificationEventType.ORDER_REJECTED -> orderDecisions
      }
}
