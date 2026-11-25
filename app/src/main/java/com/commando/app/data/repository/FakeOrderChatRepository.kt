package com.commando.app.data.repository

// AI assistance: OpenAI Codex.
import com.commando.app.model.*
import com.commando.app.model.CHAT_WINDOW_SIZE
import com.commando.app.model.ChatMessage
import com.commando.app.model.OrderChat
import com.commando.app.model.OrderStatus
import java.time.Clock
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Trusted fake order context, supplied from test/development fixtures rather than chat input. */
data class FakeChatOrder(
    val requesterId: String,
    val ownerId: String,
    val status: OrderStatus,
    val tripStatus: TripStatus = TripStatus.PUBLISHED,
)

/** Share one store between participants; updating orders also updates live send eligibility. */
class FakeOrderChatStore(val orders: StateFlow<Map<String, FakeChatOrder>>) {
  private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
  internal val messages = _messages.asStateFlow()

  internal fun append(orderId: String, message: ChatMessage) {
    _messages.value = _messages.value + (orderId to (_messages.value[orderId].orEmpty() + message))
  }

  internal fun respond(orderId: String, message: ChatMessage) {
    _messages.value =
        _messages.value +
            (orderId to
                _messages.value.getValue(orderId).map { if (it.id == message.id) message else it })
  }

  internal val mutex = Mutex()
}

/** Shared fake with the production participant, status, validation and retry contract. */
class FakeOrderChatRepository(
    private val store: FakeOrderChatStore,
    private val currentUserId: StateFlow<String?>,
    private val clock: Clock = Clock.systemUTC(),
) : OrderChatRepository {
  override suspend fun sendMessage(
      orderId: String,
      messageId: String,
      text: String,
  ): OrderResult<Unit> = send(orderId, messageId, text, null)

  override suspend fun sendDelayNotice(orderId: String, messageId: String, minutes: Int) =
      send(orderId, messageId, CHAT_DELAY_FALLBACK, minutes)

  override suspend fun proposeMeetup(orderId: String, messageId: String, proposal: MeetupProposal) =
      send(orderId, messageId, CHAT_MEETUP_FALLBACK, null, proposal)

  override suspend fun respondToMeetup(
      orderId: String,
      messageId: String,
      response: MeetupResponse,
  ): OrderResult<Unit> =
      store.mutex.withLock {
        val uid = currentUserId.value ?: return OrderResult.Error(OrderError.NotAuthenticated)
        if (
            !orderId.isDocumentId() ||
                !messageId.isDocumentId() ||
                response == MeetupResponse.PENDING
        )
            return OrderResult.Error(OrderError.InvalidData)
        val order = store.orders.value[orderId] ?: return OrderResult.Error(OrderError.NotFound)
        if (order.requesterId != uid) return OrderResult.Error(OrderError.PermissionDenied)
        val message =
            store.messages.value[orderId]?.find { it.id == messageId }
                ?: return OrderResult.Error(OrderError.NotFound)
        if (message.senderId == uid) return OrderResult.Error(OrderError.PermissionDenied)
        val meetup = message.meetup ?: return OrderResult.Error(OrderError.InvalidData)
        if (meetup.response == response) return OrderResult.Success(Unit)
        if (meetup.response != MeetupResponse.PENDING)
            return OrderResult.Error(OrderError.InvalidData)
        if (
            !order.status.canSendChat ||
                !order.tripStatus.canReportDelay ||
                !meetup.at.isAfter(clock.instant())
        )
            return OrderResult.Error(OrderError.PermissionDenied)
        store.respond(
            orderId,
            message.copy(meetup = meetup.copy(response = response, respondedAt = clock.instant())),
        )
        OrderResult.Success(Unit)
      }

  private suspend fun send(
      orderId: String,
      messageId: String,
      text: String,
      delay: Int?,
      meetup: MeetupProposal? = null,
  ): OrderResult<Unit> =
      store.mutex.withLock {
        val uid =
            currentUserId.value?.takeIf { it.isDocumentId() }
                ?: return OrderResult.Error(OrderError.NotAuthenticated)
        if (
            !orderId.isDocumentId() ||
                !messageId.isDocumentId() ||
                !isValidChatPayload(text, delay, meetup)
        )
            return OrderResult.Error(OrderError.InvalidData)
        val accessError = access(orderId, uid)
        if (accessError != null) return OrderResult.Error(accessError)
        val messages = store.messages.value[orderId].orEmpty()
        val previous = messages.find { it.id == messageId }
        when {
          previous != null &&
              (previous.senderId != uid ||
                  previous.text != text ||
                  previous.delayMinutes != delay ||
                  previous.meetup?.copy(response = MeetupResponse.PENDING, respondedAt = null) !=
                      meetup) -> OrderResult.Error(OrderError.InvalidData)
          previous != null -> OrderResult.Success(Unit)
          meetup != null && !meetup.isFuturePending(clock.instant()) ->
              OrderResult.Error(OrderError.InvalidData)
          (delay != null || meetup != null) &&
              store.orders.value.getValue(orderId).let {
                it.ownerId != uid || !it.tripStatus.canReportDelay
              } -> OrderResult.Error(OrderError.PermissionDenied)
          !store.orders.value.getValue(orderId).status.canSendChat ->
              OrderResult.Error(OrderError.PermissionDenied)
          else -> {
            store.append(orderId, ChatMessage(messageId, uid, text, clock.instant(), delay, meetup))
            OrderResult.Success(Unit)
          }
        }
      }

  override fun observeChat(orderId: String): Flow<OrderResult<OrderChat>> = flow {
    val uid = currentUserId.value?.takeIf { it.isDocumentId() }
    emitAll(
        combine(store.orders, store.messages, currentUserId) { orders, messages, sessionUid ->
              val error =
                  when {
                    uid == null || sessionUid != uid -> OrderError.NotAuthenticated
                    !orderId.isDocumentId() -> OrderError.InvalidData
                    else -> access(orderId, uid)
                  }
              if (error != null) OrderResult.Error(error)
              else
                  OrderResult.Success(
                      OrderChat(
                          orders.getValue(orderId).status,
                          messages[orderId]
                              .orEmpty()
                              .sortedWith(compareBy<ChatMessage> { it.createdAt }.thenBy { it.id })
                              .takeLast(CHAT_WINDOW_SIZE.toInt()),
                      )
                  )
            }
            .transformWhile {
              emit(it)
              it is OrderResult.Success
            }
    )
  }

  private fun access(orderId: String, uid: String): OrderError? {
    val order = store.orders.value[orderId] ?: return OrderError.NotFound
    return if (
        order.status == OrderStatus.PENDING || (uid != order.requesterId && uid != order.ownerId)
    )
        OrderError.PermissionDenied
    else null
  }
}
