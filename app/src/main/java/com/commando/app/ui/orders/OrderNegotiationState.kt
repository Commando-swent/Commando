package com.commando.app.ui.orders

// AI assistance: OpenAI Codex.
import com.commando.app.model.OrderNegotiation

/** Feedback is separate from the last confirmed offer; failures never imply acceptance. */
enum class NegotiationNotice {
  SAVED,
  SUPERSEDED,
  OFFLINE,
  FAILED,
  UNCERTAIN,
}

sealed interface OrderNegotiationState {
  data object Loading : OrderNegotiationState

  data object Unavailable : OrderNegotiationState

  data object SessionEnded : OrderNegotiationState

  data class Content(
      val snapshot: OrderNegotiation,
      val viewerId: String,
      val online: Boolean = true,
      val busy: Boolean = false,
      val notice: NegotiationNotice? = null,
  ) : OrderNegotiationState {
    val canAct: Boolean
      get() =
          online && !busy && notice != NegotiationNotice.UNCERTAIN && snapshot.canRespond(viewerId)
  }
}
