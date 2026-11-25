package com.commando.app.ui.chat

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

/** Owns saved structured mutations for the chat ViewModel. */
internal class MeetupActions(
    private val orderId: String,
    private val repository: OrderChatRepository,
    private val chat: MutableStateFlow<OrderChatUiState>,
    saved: SavedStateHandle,
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
    if (!chat.value.canSendDelay) return
    perform(PendingMeetup(UUID.randomUUID().toString(), proposal))
  }

  fun respond(id: String, response: MeetupResponse) {
    if (!chat.value.canEditDraft || response == MeetupResponse.PENDING) return
    perform(PendingMeetup(id, response = response))
  }

  fun retry() {
    val action = outbox.pending ?: failed ?: return
    perform(
        if (!hasPending && action.proposal != null) action.copy(id = UUID.randomUUID().toString())
        else action
    )
  }

  private fun perform(action: PendingMeetup) {
    val current = chat.value
    val uid = current.context?.viewerId ?: return
    if (!current.canWrite || mutable.value.busy || (!hasPending && !current.canEditDraft)) return
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
      if (chat.value.context?.viewerId != uid || outbox.pending != action) return@launch
      if (result is OrderResult.Success) clear()
      else {
        val error = (result as OrderResult.Error).error
        failed = action
        if (error != OrderError.Unknown) outbox.pending = null
        mutable.value = MeetupActionState(error = error)
        chat.update { it.copy(sending = error == OrderError.Unknown) }
        reconcile()
      }
    }
  }

  fun reconcile() {
    val action = outbox.pending ?: return
    val message =
        chat.value.chat?.messages?.find { it.id == action.id }
            ?: run {
              chat.update { it.copy(sending = true) }
              return
            }
    val proposal = message.meetup ?: return
    val confirmed =
        action.proposal?.let {
          message.senderId == chat.value.context?.viewerId &&
              proposal.copy(response = MeetupResponse.PENDING, respondedAt = null) == it
        } ?: (proposal.response != MeetupResponse.PENDING)
    if (confirmed) clear()
  }

  fun stop() {
    job?.cancel()
    mutable.value = MeetupActionState(error = if (hasPending) OrderError.Unknown else null)
  }

  fun clear() {
    outbox.pending = null
    failed = null
    mutable.value = MeetupActionState()
    chat.update { it.copy(sending = false) }
  }
}
