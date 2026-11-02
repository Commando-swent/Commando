package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.NotificationPreferences
import com.android.sample.model.OrderNotification

/** Private inbox/preferences using session identity and existing order results/errors. */
interface NotificationRepository {
  /** Processes events once; visible entries sort newest first, then ID. Muted events stay muted. */
  suspend fun getNotifications(): OrderResult<List<OrderNotification>>

  /** Idempotently marks an own notification read; foreign or missing IDs return NotFound. */
  suspend fun markAsRead(notificationId: String): OrderResult<Unit>

  /** Current account's settings, or the default settings when nothing has been saved. */
  suspend fun getPreferences(): OrderResult<NotificationPreferences>

  /** Saves only non-null toggles, preserving independent changes on other devices. */
  suspend fun updatePreferences(
      newOrders: Boolean? = null,
      orderDecisions: Boolean? = null,
  ): OrderResult<Unit>
}
