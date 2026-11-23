package com.commando.app.data.repository

// AI assistance: OpenAI Codex.
import com.commando.app.model.OrderChat
import kotlinx.coroutines.flow.Flow

/** Order-scoped text conversation; authorization always uses persisted participants. */
interface OrderChatRepository {
  /**
   * Server-confirmed latest window and status, including terminal history. Pending is locked. A
   * failure emits Error and ends collection; collect again to retry. Collect only during the
   * foreground screen/session lifetime; cancellation removes listeners. Account changes end the
   * stream with NotAuthenticated, and the caller must clear its previous screen state.
   * Cache-only/offline snapshots emit nothing: the UI must bound its initial loading wait and offer
   * retry (PR 2), rather than infer an empty conversation or enable offline sending.
   */
  fun observeChat(orderId: String): Flow<OrderResult<OrderChat>>

  /**
   * Caller generates one document ID per draft and keeps it on failure. An identical committed
   * retry succeeds read-only even after delivery; a reused ID with different sender/text returns
   * InvalidData. New sends on locked orders return PermissionDenied. Unknown/NotAuthenticated or
   * cancellation may follow a server commit: reconcile using the same ID, never a fresh ID.
   * Identity must be session-bound (invalidated even if the original account later returns).
   */
  suspend fun sendMessage(orderId: String, messageId: String, text: String): OrderResult<Unit>
}
