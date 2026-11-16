package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.*
import java.time.Clock
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Trusted fake order context, supplied from test/development fixtures rather than chat input. */
data class FakeChatOrder(val requesterId: String, val ownerId: String, val status: OrderStatus)

/** Share one store between participants; updating orders also updates live send eligibility. */
class FakeOrderChatStore(val orders: StateFlow<Map<String, FakeChatOrder>>) {
  internal val messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
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
  ): OrderResult<Unit> =
      store.mutex.withLock {
        val uid =
            currentUserId.value?.takeIf { it.isDocumentId() }
                ?: return OrderResult.Error(OrderError.NotAuthenticated)
        if (!orderId.isDocumentId() || !messageId.isDocumentId() || !text.isValidChatText())
            return OrderResult.Error(OrderError.InvalidData)
        val access = access(orderId, uid)
        if (access != null) return OrderResult.Error(access)
        val messages = store.messages.value[orderId].orEmpty()
        val previous = messages.find { it.id == messageId }
        when {
          previous != null && (previous.senderId != uid || previous.text != text) ->
              OrderResult.Error(OrderError.InvalidData)
          previous != null -> OrderResult.Success(Unit)
          !store.orders.value.getValue(orderId).status.canSendChat ->
              OrderResult.Error(OrderError.PermissionDenied)
          else -> {
            store.messages.value =
                store.messages.value +
                    (orderId to (messages + ChatMessage(messageId, uid, text, clock.instant())))
            OrderResult.Success(Unit)
          }
        }
      }

  override fun observeChat(orderId: String): Flow<OrderResult<OrderChat>> = flow {
    val uid = currentUserId.value?.takeIf { it.isDocumentId() }
    emitAll(
        combine(store.orders, store.messages, currentUserId) { orders, messages, session ->
              val error =
                  when {
                    uid == null || session != uid -> OrderError.NotAuthenticated
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
