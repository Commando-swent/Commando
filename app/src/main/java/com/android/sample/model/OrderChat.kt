package com.android.sample.model

// AI assistance: OpenAI Codex.
import com.commando.app.model.OrderStatus
import java.time.Instant

/** UTF-16 code units, matching Kotlin String.length; mirrored in firestore.rules. */
const val MAX_CHAT_TEXT_LENGTH = 1000
/** Latest messages retained in the live window; mirrored by the rules' query limit. */
const val CHAT_WINDOW_SIZE = 100L

/** Immutable server-confirmed text; ID belongs to the order's messages subcollection. */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val createdAt: Instant,
)

/** Chronological live window; equal timestamps are ordered by message ID. */
data class OrderChat(val status: OrderStatus, val messages: List<ChatMessage>)

/** One shared send policy for the repository, fake and future composer. */
val OrderStatus.canSendChat: Boolean
  get() = this == OrderStatus.ACCEPTED || this == OrderStatus.READY_FOR_HANDOFF

/** Preserve the original text, rejecting blank input and oversized UTF-16 strings. */
fun String.isValidChatText(): Boolean = isNotBlank() && length <= MAX_CHAT_TEXT_LENGTH
