package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.*
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Shared schema for atomic publication and recipient-side processing; mirrored in firestore.rules.
 */
internal object NotificationFirestoreData {
  const val EVENTS = "orderEvents"
  const val INBOX = "notifications"
  const val PREFERENCES = "notificationPreferences"
  const val SETTINGS = "settings"
  const val ORDER_ID = "orderId"
  const val RECIPIENT_ID = "recipientId"
  const val TYPE = "type"
  const val OCCURRED_AT = "occurredAt"
  const val VISIBLE = "visible"
  const val IS_READ = "isRead"
  const val NEW_ORDERS = "newOrders"
  const val ORDER_DECISIONS = "orderDecisions"
  val EVENT_FIELDS = setOf(ORDER_ID, RECIPIENT_ID, TYPE, OCCURRED_AT)
  val INBOX_FIELDS = EVENT_FIELDS + setOf(VISIBLE, IS_READ)

  /** Encodes only a typed event; timestamp conversion stays in this persistence layer. */
  fun eventData(event: OrderNotificationEvent) =
      mapOf(
          ORDER_ID to event.orderId,
          RECIPIENT_ID to event.recipientId,
          TYPE to event.type.name,
          OCCURRED_AT to Timestamp(event.occurredAt.epochSecond, event.occurredAt.nano),
      )

  /** Rejects malformed or non-canonical event identities instead of partially decoding them. */
  fun decodeEvent(id: String, data: Map<String, Any?>): OrderNotificationEvent? {
    if (data.keys != EVENT_FIELDS) return null
    val order = (data[ORDER_ID] as? String)?.takeIf { it.isDocumentId() } ?: return null
    val recipient = (data[RECIPIENT_ID] as? String)?.takeIf { it.isDocumentId() } ?: return null
    val type = NotificationEventType.entries.find { it.name == data[TYPE] } ?: return null
    val time = data[OCCURRED_AT] as? Timestamp ?: return null
    if (id != type.eventId(order)) return null
    return OrderNotificationEvent(order, recipient, type, time.toInstant())
  }

  /** Shared preference encoding for the adapter and its authenticated test clients. */
  fun preferenceData(preferences: NotificationPreferences) =
      mapOf(NEW_ORDERS to preferences.newOrders, ORDER_DECISIONS to preferences.orderDecisions)

  /** Missing settings use defaults; malformed stored settings fail closed. */
  fun decodePreferences(data: Map<String, Any?>?): NotificationPreferences? {
    if (data == null) return NotificationPreferences()
    if (data.keys != setOf(NEW_ORDERS, ORDER_DECISIONS)) return null
    return NotificationPreferences(
        data[NEW_ORDERS] as? Boolean ?: return null,
        data[ORDER_DECISIONS] as? Boolean ?: return null,
    )
  }
}

internal fun FirebaseFirestore.notificationUser(uid: String) = collection("users").document(uid)
