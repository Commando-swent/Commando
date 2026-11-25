package com.commando.app.model

// AI assistance: OpenAI Codex.
import java.time.Instant

/** UTF-16 code units, matching Kotlin String.length; mirrored in firestore.rules. */
const val MAX_CHAT_TEXT_LENGTH = 1000
/** Latest messages retained in the live window; mirrored by the rules' query limit. */
const val CHAT_WINDOW_SIZE = 100L
/** Delay notices are whole minutes from 1 to 240; mirrored in the Firestore rules. */
const val MAX_CHAT_DELAY_MINUTES = 240
/** Readable by older clients; updated clients localize the structured duration. */
const val CHAT_MEETUP_FALLBACK = "The commando proposed a meetup. Open the updated app to respond."
const val CHAT_DELAY_FALLBACK = "The commando is running late."

/** Immutable server-confirmed message; ID belongs to the order's messages subcollection. */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val createdAt: Instant,
    /**
     * Null is legacy/plain text; a delay notice has readable fallback text and a validated
     * duration.
     */
    val delayMinutes: Int? = null,
    val meetup: MeetupProposal? = null,
)

/** Chronological live window; equal timestamps are ordered by message ID. */
data class OrderChat(
    val status: OrderStatus,
    val messages: List<ChatMessage>,
    /** False is an unconfirmed marker; neither its status nor its messages are authoritative. */
    val serverConfirmed: Boolean = true,
)

/** One shared send policy for the repository, fake and composer. */
val OrderStatus.canSendChat: Boolean
  get() = this == OrderStatus.ACCEPTED || this == OrderStatus.READY_FOR_HANDOFF

/** Preserve the original text, rejecting blank input and oversized UTF-16 strings. */
fun String.isValidChatText(): Boolean = isNotBlank() && length <= MAX_CHAT_TEXT_LENGTH

/** Canonical variants prevent ambiguous text-plus-delay messages and preserve legacy text. */
fun isValidChatPayload(text: String, delayMinutes: Int?, meetup: MeetupProposal? = null): Boolean =
    if (meetup != null)
        delayMinutes == null &&
            text == CHAT_MEETUP_FALLBACK &&
            meetup.location.isValidChatText() &&
            (meetup.respondedAt != null) == (meetup.response != MeetupResponse.PENDING)
    else if (delayMinutes == null) text.isValidChatText()
    else text == CHAT_DELAY_FALLBACK && delayMinutes in 1..MAX_CHAT_DELAY_MINUTES

/** Closing a trip to new orders does not prevent coordinating existing accepted orders. */
val TripStatus.canReportDelay: Boolean
  get() = this == TripStatus.PUBLISHED || this == TripStatus.IN_PROGRESS

/** Only a requester may finalize a pending proposal; committed responses are immutable. */
enum class MeetupResponse {
  PENDING,
  ACCEPTED,
  DECLINED,
}

/** Immutable proposed place/time and the server-confirmed decision retained in chat history. */
data class MeetupProposal(
    val location: String,
    val at: Instant,
    val response: MeetupResponse = MeetupResponse.PENDING,
    val respondedAt: Instant? = null,
)

/** Expired/final proposals remain readable but cannot be created as new pending offers. */
fun MeetupProposal.isFuturePending(now: Instant): Boolean =
    response == MeetupResponse.PENDING && at.isAfter(now)
