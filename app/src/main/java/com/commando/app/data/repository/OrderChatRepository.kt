package com.commando.app.data.repository

// AI assistance: OpenAI Codex.
import com.commando.app.model.MeetupProposal
import com.commando.app.model.MeetupResponse
import com.commando.app.model.OrderChat
import kotlinx.coroutines.flow.Flow

/** Order-scoped conversation; authorization always uses persisted participants. */
interface OrderChatRepository {
  /**
   * Server-confirmed latest window and status, including terminal history. Pending is locked. A
   * failure emits Error and ends collection; collect again to retry. Collect only during the
   * foreground screen/session lifetime; cancellation removes listeners. Account changes end the
   * stream with NotAuthenticated, and the caller must clear its previous screen state.
   * Cache-only/pending snapshots emit a serverConfirmed=false marker with no messages. It is never
   * an authoritative empty history or permission to send. The UI may retain its own previously
   * confirmed history read-only and must bound its initial loading wait.
   */
  fun observeChat(orderId: String): Flow<OrderResult<OrderChat>>

  /**
   * Caller generates one document ID per draft and keeps it on failure. An identical committed
   * retry succeeds read-only even after delivery; a reused ID with different sender/payload returns
   * InvalidData. New sends on locked orders return PermissionDenied. Unknown/NotAuthenticated or
   * cancellation may follow a server commit: reconcile using the same ID, never a fresh ID.
   * Identity must be session-bound (invalidated even if the original account later returns).
   */
  suspend fun sendMessage(orderId: String, messageId: String, text: String): OrderResult<Unit>

  /**
   * Persisted trip owner only; whole minutes in 1..MAX_CHAT_DELAY_MINUTES. Reuse the message ID for
   * uncertain outcomes. Identical committed retries stay read-only after terminal transitions.
   * Stored as readable fallback text plus delayMinutes; only ACCEPTED/READY_FOR_HANDOFF allow new
   * notices.
   */
  suspend fun sendDelayNotice(orderId: String, messageId: String, minutes: Int): OrderResult<Unit>

  /** Owner-only future proposal. Identical same-ID retries remain read-only after a response. */
  suspend fun proposeMeetup(
      orderId: String,
      messageId: String,
      proposal: MeetupProposal,
  ): OrderResult<Unit>

  /** Requester-only first decision wins atomically; identical repeats succeed without writing. */
  suspend fun respondToMeetup(
      orderId: String,
      messageId: String,
      response: MeetupResponse,
  ): OrderResult<Unit>
}
