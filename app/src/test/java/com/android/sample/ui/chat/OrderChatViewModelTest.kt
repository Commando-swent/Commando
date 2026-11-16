package com.android.sample.ui.chat

// AI assistance: OpenAI Codex.
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.android.sample.MainDispatcherRule
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.commando.app.model.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Authorization, lifecycle cleanup and uncertain outcomes exercise the real ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderChatViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val user = MutableStateFlow<String?>("requester")
  private val now = Instant.parse("2030-01-01T12:00:00Z")
  private val trip =
      Trip(
          "trip",
          "owner",
          TripLocations.stores.first(),
          now,
          TripLocations.handoffPoints.first(),
          TripStatus.IN_PROGRESS,
          now,
          now,
      )
  private var order =
      Order(
          "order",
          trip.id,
          "requester",
          listOf(OrderItem("milk", "Milk", 100, 1)),
          0,
          OrderStatus.ACCEPTED,
          now,
      )
  private var load: suspend () -> OrderResult<Order> = { OrderResult.Success(order) }
  private var tripResult: TripResult<Trip> = TripResult.Success(trip)
  private val history =
      MutableStateFlow<OrderResult<OrderChat>>(
          OrderResult.Success(OrderChat(order.status, emptyList()))
      )
  private var observe: () -> Flow<OrderResult<OrderChat>> = { history }
  private val requests = mutableListOf<Pair<String, String>>()
  private var write: suspend (String, String) -> OrderResult<Unit> = { _, _ ->
    OrderResult.Success(Unit)
  }
  private var subscriptions = 0
  private val repository =
      object : OrderChatRepository {
        override fun observeChat(orderId: String) = flow {
          assertEquals(order.id, orderId)
          subscriptions++
          try {
            emitAll(observe())
          } finally {
            subscriptions--
          }
        }

        override suspend fun sendMessage(
            orderId: String,
            messageId: String,
            text: String,
        ): OrderResult<Unit> {
          assertEquals(order.id, orderId)
          requests += messageId to text
          return write(messageId, text)
        }
      }
  private val orders =
      object : OrderRepository by FakeOrderRepository({ user.value }) {
        override suspend fun getOrderById(orderId: String) = load()
      }
  private val trips =
      object : TripRepository by FakeTripRepository("owner") {
        override suspend fun getTripById(tripId: String) = tripResult
      }

  private suspend fun TestScope.withModel(
      saved: SavedStateHandle = SavedStateHandle(),
      block: suspend (OrderChatViewModel) -> Unit,
  ) {
    val store = ViewModelStore()
    val model = OrderChatViewModel(order.id, orders, trips, repository, { user.value }, user, saved)
    store.put("chat", model)
    try {
      block(model)
    } finally {
      store.clear()
      runCurrent()
    }
    assertEquals(0, subscriptions)
  }

  @Test
  fun successBusyClicksValidationAndLiveReadOnly() = runTest {
    withModel { model ->
      model.send()
      assertTrue(requests.isEmpty())
      model.start()
      runCurrent()
      assertTrue(model.uiState.value.context!!.partnerIsCommando)
      for (invalid in listOf("", " \n", "x".repeat(MAX_CHAT_TEXT_LENGTH + 1))) {
        model.updateDraft(invalid)
        model.send()
        assertFalse(model.uiState.value.canSend)
      }
      assertTrue(requests.isEmpty())
      val gate = CompletableDeferred<OrderResult<Unit>>()
      write = { _, _ -> gate.await() }
      model.updateDraft("hello")
      model.send()
      model.send()
      runCurrent()
      model.updateDraft("must not replace the in-flight text")
      assertEquals("hello", model.uiState.value.draft)
      assertEquals(1, requests.size)
      assertTrue(model.uiState.value.sending)
      gate.complete(OrderResult.Success(Unit))
      runCurrent()
      assertEquals("", model.uiState.value.draft)
      assertFalse(model.uiState.value.sending)
      history.value = OrderResult.Success(OrderChat(OrderStatus.DELIVERED, emptyList()))
      runCurrent()
      model.updateDraft("late")
      model.send()
      assertEquals(1, requests.size)
      assertFalse(model.uiState.value.canSend)
      model.stop()
      runCurrent()
      assertNull(model.uiState.value.context)
      assertNull(model.uiState.value.chat)
      model.start()
      runCurrent()
      assertEquals(OrderStatus.DELIVERED, model.uiState.value.chat!!.status)
    }
  }

  @Test
  fun retryReusesIdAndRestorationCannotCrossAccounts() = runTest {
    val saved = SavedStateHandle()
    write = { _, _ -> OrderResult.Error(OrderError.Unknown) }
    withModel(saved) { model ->
      model.start()
      runCurrent()
      model.updateDraft("keep me")
      model.send()
      runCurrent()
      assertEquals(OrderError.Unknown, model.uiState.value.sendError)
      model.updateDraft("do not overwrite an uncertain send")
      assertEquals("keep me", model.uiState.value.draft)
    }
    withModel(saved) { model ->
      model.start()
      runCurrent()
      write = { _, _ -> OrderResult.Success(Unit) }
      model.send()
      runCurrent()
      assertEquals(requests.first(), requests.last())
      assertEquals("", model.uiState.value.draft)
      model.updateDraft("private draft")
    }
    user.value = "owner"
    withModel(saved) { model ->
      model.start()
      runCurrent()
      assertFalse(model.uiState.value.context!!.partnerIsCommando)
      assertEquals("", model.uiState.value.draft)
      user.value = null
      runCurrent()
      assertEquals(OrderError.NotAuthenticated, model.uiState.value.error)
      assertNull(model.uiState.value.context)
      user.value = "owner"
      runCurrent()
      model.start()
      model.updateDraft("ignored")
      assertEquals(OrderError.NotAuthenticated, model.uiState.value.error)
      assertEquals("", model.uiState.value.draft)
    }
  }

  @Test
  fun uncertainCommitIsRecoveredFromMatchingServerMessage() = runTest {
    withModel { model ->
      model.start()
      runCurrent()
      write = { _, _ -> OrderResult.Error(OrderError.Unknown) }
      model.updateDraft("committed")
      model.send()
      runCurrent()
      val (id, text) = requests.single()
      history.value =
          OrderResult.Success(
              OrderChat(OrderStatus.ACCEPTED, listOf(ChatMessage(id, "owner", text, now)))
          )
      runCurrent()
      assertEquals(text, model.uiState.value.draft)
      history.value =
          OrderResult.Success(
              OrderChat(OrderStatus.DELIVERED, listOf(ChatMessage(id, "requester", text, now)))
          )
      runCurrent()
      assertEquals("", model.uiState.value.draft)
      assertNull(model.uiState.value.sendError)
      assertFalse(model.uiState.value.sending)
    }
  }

  @Test
  fun boundedOfflineLoadingRetryAndUnexpectedStreamEnd() = runTest {
    withModel { model ->
      observe = { emptyFlow() }
      model.start()
      runCurrent()
      assertEquals(OrderError.Unknown, model.uiState.value.error)
      observe = { flow { awaitCancellation() } }
      model.start()
      runCurrent()
      advanceTimeBy(OrderChatViewModel.RESPONSE_TIMEOUT_MS)
      runCurrent()
      assertEquals(OrderError.Unknown, model.uiState.value.error)
      assertFalse(model.uiState.value.loading)
      assertEquals(0, subscriptions)
      observe = { history }
      model.start()
      runCurrent()
      assertNull(model.uiState.value.error)
      assertEquals(1, subscriptions)
      model.stop()
      runCurrent()
      advanceTimeBy(OrderChatViewModel.RESPONSE_TIMEOUT_MS)
      runCurrent()
      assertTrue(model.uiState.value.loading)
      assertEquals(0, subscriptions)
    }
  }

  @Test
  fun expiredLoadingAndSendingNeverRestoreSensitiveState() = runTest {
    val gate = CompletableDeferred<OrderResult<Order>>()
    load = { withContext(NonCancellable) { gate.await() } }
    withModel { model ->
      model.start()
      runCurrent()
      user.value = "outsider"
      runCurrent()
      gate.complete(OrderResult.Success(order))
      runCurrent()
      assertEquals(OrderError.NotAuthenticated, model.uiState.value.error)
      assertNull(model.uiState.value.context)
    }
    user.value = "requester"
    load = { OrderResult.Success(order) }
    withModel { model ->
      model.start()
      runCurrent()
      write = { _, _ -> OrderResult.Error(OrderError.NotAuthenticated) }
      model.updateDraft("secret")
      model.send()
      runCurrent()
      assertEquals(OrderError.NotAuthenticated, model.uiState.value.error)
      assertEquals("", model.uiState.value.draft)
    }
  }

  @Test
  fun permissionContextAndRepositoryFailuresStayTyped() = runTest {
    for (error in
        listOf(
            OrderError.PermissionDenied,
            OrderError.NotFound,
            OrderError.NotAuthenticated,
            OrderError.InvalidData,
        )) {
      withModel { model ->
        load = { OrderResult.Error(error) }
        model.start()
        runCurrent()
        assertEquals(error, model.uiState.value.error)
      }
    }
    load = { OrderResult.Success(order) }
    for ((error, expected) in
        listOf(
            TripError.NotFound to OrderError.NotFound,
            TripError.PermissionDenied to OrderError.PermissionDenied,
            TripError.Unknown to OrderError.Unknown,
        )) {
      tripResult = TripResult.Error(error)
      withModel { model ->
        model.start()
        runCurrent()
        assertEquals(expected, model.uiState.value.error)
      }
    }
    tripResult = TripResult.Success(trip)
    for (invalid in
        listOf(
            order.copy(status = OrderStatus.PENDING),
            order.copy(tripId = "wrong"),
            order.copy(requesterId = "outsider"),
        )) {
      load = { OrderResult.Success(invalid) }
      withModel { model ->
        model.start()
        runCurrent()
        assertEquals(OrderError.PermissionDenied, model.uiState.value.error)
      }
    }
    load = { throw IllegalStateException("offline") }
    withModel { model ->
      model.start()
      runCurrent()
      assertEquals(OrderError.Unknown, model.uiState.value.error)
    }
  }

  @Test
  fun stalledAndThrowingSendsKeepTheirIdAndBackgroundingCancelsThem() = runTest {
    withModel { model ->
      model.start()
      runCurrent()
      write = { _, _ -> awaitCancellation() }
      model.updateDraft("pending")
      model.send()
      runCurrent()
      advanceTimeBy(OrderChatViewModel.RESPONSE_TIMEOUT_MS)
      runCurrent()
      assertEquals(OrderError.Unknown, model.uiState.value.sendError)
      assertEquals("pending", model.uiState.value.draft)
      write = { _, _ -> throw IllegalStateException("connection lost") }
      model.send()
      runCurrent()
      assertEquals(requests.first(), requests.last())
      write = { _, _ -> awaitCancellation() }
      model.send()
      runCurrent()
      model.stop()
      runCurrent()
      assertFalse(model.uiState.value.sending)
      assertEquals("pending", model.uiState.value.draft)
      model.start()
      runCurrent()
      history.value = OrderResult.Error(OrderError.PermissionDenied)
      runCurrent()
      assertEquals(OrderError.PermissionDenied, model.uiState.value.error)
      assertNull(model.uiState.value.chat)
      history.value = OrderResult.Success(OrderChat(OrderStatus.DELIVERED, emptyList()))
      model.start()
      runCurrent()
      write = { _, _ -> OrderResult.Success(Unit) }
      model.send()
      runCurrent()
      assertEquals(requests.first(), requests.last())
      assertEquals("", model.uiState.value.draft)
    }
  }

  @Test
  fun committedSnapshotBeforeFailedAcknowledgementStillClearsTheDraft() = runTest {
    withModel { model ->
      model.start()
      runCurrent()
      val acknowledgement = CompletableDeferred<OrderResult<Unit>>()
      write = { _, _ -> acknowledgement.await() }
      model.updateDraft("acknowledged by stream")
      model.send()
      runCurrent()
      val (id, text) = requests.single()
      history.value =
          OrderResult.Success(
              OrderChat(OrderStatus.ACCEPTED, listOf(ChatMessage(id, "requester", text, now)))
          )
      runCurrent()
      assertTrue(model.uiState.value.sending)
      acknowledgement.complete(OrderResult.Error(OrderError.Unknown))
      runCurrent()
      assertEquals("", model.uiState.value.draft)
      assertNull(model.uiState.value.sendError)
    }
  }

  @Test
  fun accountChangesDuringContextStreamAndSendCallbacksAreRejected() = runTest {
    user.value = null
    withModel { model ->
      model.start()
      model.updateDraft("ignored")
      assertEquals(OrderError.NotAuthenticated, model.uiState.value.error)
    }
    user.value = "requester"
    load = {
      user.value = "outsider"
      OrderResult.Success(order)
    }
    withModel { model ->
      model.start()
      runCurrent()
      assertEquals(OrderError.NotAuthenticated, model.uiState.value.error)
      assertNull(model.uiState.value.context)
    }
    user.value = "requester"
    load = { OrderResult.Success(order) }
    observe = {
      flow {
        user.value = "outsider"
        emit(OrderResult.Success(OrderChat(order.status, emptyList())))
      }
    }
    withModel { model ->
      model.start()
      runCurrent()
      assertEquals(OrderError.NotAuthenticated, model.uiState.value.error)
      assertNull(model.uiState.value.chat)
    }
    user.value = "requester"
    observe = { history }
    withModel { model ->
      model.start()
      runCurrent()
      write = { _, _ ->
        user.value = "outsider"
        OrderResult.Success(Unit)
      }
      model.updateDraft("private")
      model.send()
      runCurrent()
      assertEquals(OrderError.NotAuthenticated, model.uiState.value.error)
      assertEquals("", model.uiState.value.draft)
    }
  }

  @Test
  fun permissionLossWhileSendingCannotRestoreMessagesAndUnknownIdsCannotClearDraft() = runTest {
    withModel { model ->
      model.start()
      runCurrent()
      val acknowledgement = CompletableDeferred<OrderResult<Unit>>()
      write = { _, _ -> acknowledgement.await() }
      model.updateDraft("private draft")
      model.send()
      runCurrent()
      history.value = OrderResult.Error(OrderError.PermissionDenied)
      runCurrent()
      acknowledgement.complete(OrderResult.Error(OrderError.Unknown))
      runCurrent()
      assertEquals(OrderError.PermissionDenied, model.uiState.value.error)
      assertNull(model.uiState.value.chat)
      val (id, text) = requests.single()
      for (message in
          listOf(
              ChatMessage("unrelated", "requester", text, now),
              ChatMessage(id, "requester", "different", now),
          )) {
        history.value = OrderResult.Success(OrderChat(order.status, listOf(message)))
        model.start()
        runCurrent()
        assertEquals(text, model.uiState.value.draft)
      }
    }
  }
}
