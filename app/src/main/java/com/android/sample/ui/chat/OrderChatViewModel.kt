package com.android.sample.ui.chat

// AI assistance: OpenAI Codex.
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.ui.session.SessionGuard
import com.commando.app.data.repository.TripError
import com.commando.app.data.repository.TripRepository
import com.commando.app.data.repository.TripResult
import com.commando.app.model.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Persisted order/trip context; the opposite participant uses a localized role fallback. */
data class ChatContext(val order: Order, val trip: Trip, val viewerId: String) {
  val partnerIsCommando: Boolean
    get() = viewerId == order.requesterId
}

/** Only confirmed messages are rendered; failed outbound text remains in the composer for retry. */
data class OrderChatUiState(
    val context: ChatContext? = null,
    val chat: OrderChat? = null,
    val loading: Boolean = true,
    val error: OrderError? = null,
    val draft: String = "",
    val sending: Boolean = false,
    val sendError: OrderError? = null,
) {
  /** Unknown outcomes keep the submitted text immutable until the server reconciles it. */
  val canEditDraft: Boolean
    get() = !sending && sendError != OrderError.Unknown

  val canSendDelay: Boolean
    get() =
        !loading &&
            error == null &&
            canEditDraft &&
            context?.let { it.viewerId == it.trip.ownerId } == true &&
            chat?.status?.canSendChat == true

  val canSend: Boolean
    get() =
        !loading &&
            error == null &&
            !sending &&
            chat?.status?.canSendChat == true &&
            draft.isValidChatText()
}

/**
 * Owns one session's chat and a saved outbound ID. Foreground collection is explicitly started and
 * stopped by the route. Unknown outcomes retain the original ID/text, including after recreation.
 */
class OrderChatViewModel(
    private val orderId: String,
    private val orders: OrderRepository,
    private val trips: TripRepository,
    private val messages: OrderChatRepository,
    currentUserId: () -> String?,
    sessionChanges: Flow<String?>,
    private val saved: SavedStateHandle,
) : ViewModel() {
  private val session = SessionGuard(currentUserId)
  private val mutableState = MutableStateFlow(OrderChatUiState())
  val uiState = mutableState.asStateFlow()
  private var observer: Job? = null
  private var timeout: Job? = null
  private var sender: Job? = null
  private var active = false
  private var ended = false

  init {
    if (session.initialUserId == null || saved.get<String>(USER) != session.initialUserId) {
      saved[DRAFT] = ""
      saved[OUTBOUND] = null
      saved[DELAY] = null
    }
    if (saved.get<String>(OUTBOUND) == null) saved[DELAY] = null
    saved[USER] = session.initialUserId
    mutableState.value =
        OrderChatUiState(
            draft = saved[DRAFT] ?: "",
            sendError = if (saved.get<String>(OUTBOUND) != null) OrderError.Unknown else null,
        )
    viewModelScope.launch { sessionChanges.collect { if (session.hasEnded(it)) endSession() } }
  }

  /** Starts/retries a single subscription, with a bounded first server response. */
  fun start() {
    stop()
    if (invalidSession()) return
    active = true
    mutableState.update { it.copy(loading = true, error = null) }
    val initialTimeout = viewModelScope.launch {
      delay(RESPONSE_TIMEOUT_MS)
      if (mutableState.value.loading) {
        observer?.cancel()
        fail(OrderError.Unknown)
      }
    }
    timeout = initialTimeout
    observer = viewModelScope.launch { observeConversation(initialTimeout) }
  }

  private suspend fun observeConversation(initialTimeout: Job) {
    try {
      val context = loadContext()
      currentCoroutineContext().ensureActive()
      if (invalidSession()) return
      mutableState.update { it.copy(context = context) }
      messages.observeChat(orderId).collect { result ->
        currentCoroutineContext().ensureActive()
        if (invalidSession()) return@collect
        initialTimeout.cancel()
        receive(result)
      }
      if (mutableState.value.error == null) fail(OrderError.Unknown)
    } catch (exception: Exception) {
      currentCoroutineContext().ensureActive()
      if (!invalidSession()) fail((exception as? ChatLoadFailure)?.error ?: OrderError.Unknown)
    } finally {
      initialTimeout.cancel()
    }
  }

  private fun receive(result: OrderResult<OrderChat>) {
    when (result) {
      is OrderResult.Error -> fail(result.error)
      is OrderResult.Success -> {
        mutableState.update { it.copy(chat = result.data, loading = false, error = null) }
        if (!mutableState.value.sending && confirmed(saved[OUTBOUND])) completeSend()
      }
    }
  }

  /**
   * Drops private displayed context and cancels collection/sending; the same draft ID is retained.
   */
  fun stop() {
    active = false
    if (saved.get<String>(OUTBOUND) == null) saved[DELAY] = null
    observer?.cancel()
    timeout?.cancel()
    sender?.cancel()
    mutableState.value =
        OrderChatUiState(
            draft = saved[DRAFT] ?: "",
            sendError = if (saved.get<String>(OUTBOUND) != null) OrderError.Unknown else null,
        )
  }

  /** A failed uncertain send is immutable until retried or reconciled by its live server record. */
  fun updateDraft(text: String) {
    if (invalidSession() || mutableState.value.sending || saved.get<String>(OUTBOUND) != null)
        return
    saved[DELAY] = null
    saved[DRAFT] = text
    mutableState.update { it.copy(draft = text, sendError = null) }
  }

  /** Reuses the saved ID/text on retry; busy taps and new sends in read-only states are ignored. */
  fun send() = sendPending()

  /** Only the commando can report a delay; a typed message draft remains untouched. */
  fun sendDelay(minutes: Int) {
    if (
        !active ||
            invalidSession() ||
            !mutableState.value.canSendDelay ||
            minutes !in 1..MAX_CHAT_DELAY_MINUTES ||
            saved.get<String>(OUTBOUND) != null
    )
        return
    saved[DELAY] = minutes
    sendPending()
  }

  private fun sendPending() {
    if (!active || invalidSession() || mutableState.value.sending) return
    val existing = saved.get<String>(OUTBOUND)
    val minutes = saved.get<Int>(DELAY)
    if (
        existing == null &&
            !(if (minutes == null) mutableState.value.canSend else mutableState.value.canSendDelay)
    )
        return
    val id = existing ?: UUID.randomUUID().toString()
    val text = if (minutes == null) saved.get<String>(DRAFT).orEmpty() else ""
    saved[OUTBOUND] = id
    mutableState.update { it.copy(sending = true, sendError = null) }
    sender = viewModelScope.launch {
      val result =
          try {
            withTimeout(RESPONSE_TIMEOUT_MS) {
              if (minutes == null) messages.sendMessage(orderId, id, text)
              else messages.sendDelayNotice(orderId, id, minutes)
            }
          } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            OrderResult.Error(OrderError.Unknown)
          }
      currentCoroutineContext().ensureActive()
      finishSend(id, result)
    }
  }

  private fun finishSend(id: String, result: OrderResult<Unit>) {
    if (!active || invalidSession() || saved.get<String>(OUTBOUND) != id) return
    if (result is OrderResult.Success || confirmed(id)) completeSend()
    else {
      val error = (result as OrderResult.Error).error
      if (error == OrderError.NotAuthenticated) endSession()
      else {
        // Only Unknown may represent a committed write whose acknowledgement was lost.
        if (error != OrderError.Unknown) {
          saved[OUTBOUND] = null
        }
        mutableState.update { it.copy(sending = false, sendError = error) }
      }
    }
  }

  private fun confirmed(id: String?): Boolean =
      id != null &&
          mutableState.value.chat?.messages?.any {
            it.id == id &&
                it.senderId == session.initialUserId &&
                it.delayMinutes == saved.get<Int>(DELAY) &&
                it.text == if (it.delayMinutes == null) saved.get<String>(DRAFT) else ""
          } == true

  private fun completeSend() {
    saved[OUTBOUND] = null
    if (saved.get<Int>(DELAY) == null) saved[DRAFT] = ""
    saved[DELAY] = null
    mutableState.update { it.copy(draft = saved[DRAFT] ?: "", sending = false, sendError = null) }
  }

  private fun invalidSession(): Boolean {
    if (ended || session.initialUserId == null || session.hasEnded()) endSession()
    return ended
  }

  private fun endSession() {
    ended = true
    saved[DRAFT] = ""
    saved[OUTBOUND] = null
    saved[DELAY] = null
    stop()
    mutableState.value = OrderChatUiState(loading = false, error = OrderError.NotAuthenticated)
  }

  private fun fail(error: OrderError) {
    if (error == OrderError.NotAuthenticated) endSession()
    else
        mutableState.update { it.copy(context = null, chat = null, loading = false, error = error) }
  }

  private suspend fun loadContext(): ChatContext {
    val order =
        when (val result = orders.getOrderById(orderId)) {
          is OrderResult.Success -> result.data
          is OrderResult.Error -> throw ChatLoadFailure(result.error)
        }
    val trip =
        when (val result = trips.getTripById(order.tripId)) {
          is TripResult.Success -> result.data
          is TripResult.Error ->
              throw ChatLoadFailure(
                  when (result.error) {
                    TripError.PermissionDenied -> OrderError.PermissionDenied
                    TripError.NotFound -> OrderError.NotFound
                    else -> OrderError.Unknown
                  }
              )
        }
    val viewer = session.initialUserId.orEmpty()
    if (
        order.id != orderId ||
            trip.id != order.tripId ||
            order.status == OrderStatus.PENDING ||
            viewer !in listOf(order.requesterId, trip.ownerId)
    )
        throw ChatLoadFailure(OrderError.PermissionDenied)
    return ChatContext(order, trip, viewer)
  }

  /** Carries a repository failure through the shared context-loading coroutine. */
  private class ChatLoadFailure(val error: OrderError) : Exception()

  companion object {
    internal const val RESPONSE_TIMEOUT_MS = 20_000L
    private const val DRAFT = "chatDraft"
    private const val OUTBOUND = "chatOutboundId"
    private const val DELAY = "chatOutboundDelay"
    private const val USER = "chatUserId"
  }
}
