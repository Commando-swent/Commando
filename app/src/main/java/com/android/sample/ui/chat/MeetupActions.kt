package com.android.sample.ui.chat

// AI assistance: OpenAI Codex.
import androidx.lifecycle.SavedStateHandle
import com.commando.app.data.repository.OrderChatRepository
import com.commando.app.data.repository.OrderError
import com.commando.app.data.repository.OrderResult
import com.commando.app.model.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Feedback for the order-scoped structured mutation, independent of the typed message draft. */
data class MeetupActionState(val busy: Boolean = false, val error: OrderError? = null)

/**
 * Owned by the chat ViewModel; saved primitive fields retain uncertain mutations across recreation.
 */
internal class MeetupActions(
    private val orderId: String,
    private val repository: OrderChatRepository,
    private val chat: MutableStateFlow<OrderChatUiState>,
    private val saved: SavedStateHandle,
    private val scope: CoroutineScope,
) {
  private val outbox = MeetupOutbox(saved)
  private var failed: PendingMeetup? = null
  private var job: Job? = null
  private val mutable =
      MutableStateFlow(MeetupActionState(error = if (hasPending) OrderError.Unknown else null))
  val state = mutable.asStateFlow()
  val hasPending: Boolean
    get() = outbox.pending != null

  fun propose(location: String, at: Instant) {
    val proposal = MeetupProposal(location, Instant.ofEpochMilli(at.toEpochMilli()))
    if (
        !chat.value.canSendDelay ||
            !proposal.at.isAfter(Instant.now()) ||
            !isValidChatPayload(CHAT_MEETUP_FALLBACK, null, proposal)
    )
        return
    perform(PendingMeetup(UUID.randomUUID().toString(), proposal))
  }

  fun respond(id: String, response: MeetupResponse) {
    val current = chat.value
    val proposal = current.chat?.messages?.find { it.id == id }?.meetup ?: return
    if (
        !current.canEditDraft ||
            current.loading ||
            current.error != null ||
            current.context?.let {
              it.viewerId == it.order.requesterId && it.viewerId != it.trip.ownerId
            } != true ||
            current.chat.status.canSendChat != true ||
            proposal.response != MeetupResponse.PENDING ||
            response == MeetupResponse.PENDING
    )
        return
    perform(PendingMeetup(id, response = response))
  }

  fun retry() {
    val action = restored() ?: failed ?: return
    perform(
        if (!hasPending && action.proposal != null) action.copy(id = UUID.randomUUID().toString())
        else action
    )
  }

  private fun restored() = outbox.pending

  private fun perform(action: PendingMeetup) {
    val current = chat.value
    val uid = current.context?.viewerId ?: return
    if (
        current.loading ||
            current.error != null ||
            mutable.value.busy ||
            (!hasPending && !current.canEditDraft)
    )
        return
    outbox.pending = action
    chat.update { it.copy(sending = true) }
    mutable.value = MeetupActionState(busy = true)
    job = scope.launch {
      val result =
          try {
            withTimeout(OrderChatViewModel.RESPONSE_TIMEOUT_MS) {
              action.proposal?.let { repository.proposeMeetup(orderId, action.id, it) }
                  ?: repository.respondToMeetup(orderId, action.id, checkNotNull(action.response))
            }
          } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            OrderResult.Error(OrderError.Unknown)
          }
      currentCoroutineContext().ensureActive()
      if (chat.value.context?.viewerId != uid || restored() != action) return@launch
      if (result is OrderResult.Success) clear()
      else {
        val error = (result as OrderResult.Error).error
        failed = action
        if (error != OrderError.Unknown) clearSaved()
        mutable.value = MeetupActionState(error = error)
        chat.update { it.copy(sending = error == OrderError.Unknown) }
        reconcile()
      }
    }
  }

  fun reconcile() {
    val action = restored() ?: return
    val message =
        chat.value.chat?.messages?.find { it.id == action.id }
            ?: run {
              chat.update { it.copy(sending = true) }
              return
            }
    val confirmed =
        action.proposal?.let {
          message.senderId == chat.value.context?.viewerId &&
              message.meetup?.copy(response = MeetupResponse.PENDING, respondedAt = null) == it
        } ?: (message.meetup?.response == action.response)
    if (confirmed) clear()
    else if (action.response != null && message.meetup?.response != MeetupResponse.PENDING) {
      clear()
      mutable.value = MeetupActionState(error = OrderError.InvalidData)
    }
  }

  fun stop() {
    job?.cancel()
    mutable.value = MeetupActionState(error = if (hasPending) OrderError.Unknown else null)
  }

  fun clear() {
    clearSaved()
    failed = null
    mutable.value = MeetupActionState()
    chat.update { it.copy(sending = false) }
  }

  private fun clearSaved() { outbox.pending = null }
}
