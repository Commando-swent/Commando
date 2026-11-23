package com.commando.app.model

// AI assistance: OpenAI Codex.
import java.time.Instant

/** UTF-16 code units, matching Kotlin String.length; mirrored in firestore.rules. */
const val MAX_CHAT_TEXT_LENGTH = 1000
/** Latest messages retained in the live window; mirrored by the rules' query limit. */
const val CHAT_WINDOW_SIZE = 100L
/** Delay notices are whole minutes from 1 to 240; mirrored in the Firestore rules. */
const val MAX_CHAT_DELAY_MINUTES = 240

/** Immutable server-confirmed message; ID belongs to the order's messages subcollection. */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val createdAt: Instant,
    /** Null is legacy/plain text; a delay notice has empty text and a validated duration. */
    val delayMinutes: Int? = null,
)

/** Chronological live window; equal timestamps are ordered by message ID. */
data class OrderChat(val status: OrderStatus, val messages: List<ChatMessage>)

/** One shared send policy for the repository, fake and composer. */
val OrderStatus.canSendChat: Boolean
  get() = this == OrderStatus.ACCEPTED || this == OrderStatus.READY_FOR_HANDOFF

/** Preserve the original text, rejecting blank input and oversized UTF-16 strings. */
fun String.isValidChatText(): Boolean = isNotBlank() && length <= MAX_CHAT_TEXT_LENGTH

/** Canonical variants prevent ambiguous text-plus-delay messages and preserve legacy text. */
fun isValidChatPayload(text: String, delayMinutes: Int?): Boolean =
    if (delayMinutes == null) text.isValidChatText()
    else text.isEmpty() && delayMinutes in 1..MAX_CHAT_DELAY_MINUTES
