package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.NotificationPreferences
import com.android.sample.model.OrderNotification

/**
 * Recipient-only inbox and preferences. Identity comes from the authenticated session, not a UI
 * parameter. Reuses the existing order result/errors; generating events is not a screen capability.
 */
interface NotificationRepository {
  /**
   * Processes unseen events once using current preferences, then returns visible entries newest
   * first, with event ID ascending for equal timestamps. Muted events remain durably processed.
   */
  suspend fun getNotifications(): OrderResult<List<OrderNotification>>

  /** Idempotently marks an own notification read; foreign or missing IDs return NotFound. */
  suspend fun markAsRead(notificationId: String): OrderResult<Unit>

  /** Current account's settings, or the default settings when nothing has been saved. */
  suspend fun getPreferences(): OrderResult<NotificationPreferences>

  /**
   * Changes future event eligibility without hiding existing notifications or resetting read state.
   */
  suspend fun updatePreferences(preferences: NotificationPreferences): OrderResult<Unit>
}
