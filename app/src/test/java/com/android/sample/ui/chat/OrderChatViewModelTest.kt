package com.android.sample.ui.chat

// AI assistance: OpenAI Codex.
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.android.sample.MainDispatcherRule
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.data.repository.TripError
import com.commando.app.data.repository.TripRepository
import com.commando.app.data.repository.TripResult
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
  private val store = TripLocations.stores.first()
  private val handoff = TripLocations.handoffPoints.first()
  private val items = listOf(OrderItem("milk", "Milk", 100, 1))
  private val trip = Trip("trip", "owner", store, now, handoff, TripStatus.IN_PROGRESS, now, now)
  private var order = Order("order", trip.id, "requester", items, 0, OrderStatus.ACCEPTED, now)
  private var load: suspend () -> OrderResult<Order> = { OrderResult.Success(order) }
  private var tripResult: TripResult<Trip> = TripResult.Success(trip)
  private val history =
      MutableStateFlow<OrderResult<OrderChat>>(
          OrderResult.Success(OrderChat(order.status, emptyList()))
      )
  private var observe: () -> Flow<OrderResult<OrderChat>> = { history }
  private val delays = mutableListOf<Pair<String, Int>>()
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

        override suspend fun sendDelayNotice(
            orderId: String,
            messageId: String,
            minutes: Int,
        ): OrderResult<Unit> {
          delays += messageId to minutes
          return write(messageId, "")
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
      start: Boolean = true,
      block: suspend (OrderChatViewModel) -> Unit,
  ) {
    val store = ViewModelStore()
    val model = OrderChatViewModel(order.id, orders, trips, repository, { user.value }, user, saved)
    store.put("chat", model)
    try {
      if (start) startChat(model)
      block(model)
    } finally {
      store.clear()
      runCurrent()
    }
    assertEquals(0, subscriptions)
  }

  private fun TestScope.startChat(model: OrderChatViewModel) {
    model.start()
    runCurrent()
  }

  private fun TestScope.sendDraft(model: OrderChatViewModel, text: String? = null) {
    if (text != null) model.updateDraft(text)
    model.send()
    runCurrent()
  }

  private fun TestScope.publish(status: OrderStatus, message: ChatMessage) {
    history.value = OrderResult.Success(OrderChat(status, listOf(message)))
    runCurrent()
  }

  @Test
  fun delayRetryAndRestorationKeepTheSameIdAndPreserveTypedText() = runTest {
    user.value = trip.ownerId
    val saved = SavedStateHandle()
    write = { _, _ -> OrderResult.Error(OrderError.Unknown) }
    withModel(saved) { model ->
      model.updateDraft("Keep my typed message")
      for (minutes in listOf(0, MAX_CHAT_DELAY_MINUTES + 1)) model.sendDelay(minutes)
      assertTrue(delays.isEmpty())
      model.sendDelay(12)
      runCurrent()
      assertEquals(12, delays.single().second)
      assertFalse(model.uiState.value.canSendDelay)
    }
    withModel(saved) { model ->
      model.send()
      runCurrent()
      assertEquals(delays.first(), delays.last())
      publish(OrderStatus.ACCEPTED, ChatMessage(delays.first().first, trip.ownerId, "", now, 12))
      assertNull(model.uiState.value.sendError)
      assertEquals("Keep my typed message", model.uiState.value.draft)
      assertTrue(model.uiState.value.canSendDelay)
      assertTrue(requests.isEmpty())
    }
  }

  @Test
  fun requesterAndTerminalOrderCannotReportDelay() = runTest {
    withModel { model ->
      model.sendDelay(5)
      runCurrent()
      assertTrue(delays.isEmpty())
      user.value = trip.ownerId
      runCurrent()
      assertEquals(OrderError.NotAuthenticated, model.uiState.value.error)
    }
    history.value = OrderResult.Success(OrderChat(OrderStatus.DELIVERED, emptyList()))
    withModel { model ->
      model.sendDelay(5)
      runCurrent()
      assertTrue(delays.isEmpty())
    }
  }

  @Test
  fun confirmedDelayKeepsDraftAndDefinitiveFailureAllowsAnotherDuration() = runTest {
    user.value = trip.ownerId
    write = { _, _ -> OrderResult.Error(OrderError.PermissionDenied) }
    withModel { model ->
      model.updateDraft("typed")
      model.sendDelay(3)
      runCurrent()
      assertEquals(OrderError.PermissionDenied, model.uiState.value.sendError)
      assertTrue(model.uiState.value.canSendDelay)
      write = { _, _ -> OrderResult.Success(Unit) }
      model.send()
      runCurrent()
      assertEquals(3, delays.last().second)
      assertNotEquals(delays.first().first, delays.last().first)
      assertEquals("typed", model.uiState.value.draft)
      assertNull(model.uiState.value.sendError)
    }
  }

  @Test
  fun validDraftCannotSendBeforeLoadingOrAfterAccessFailure() = runTest {
    observe = { flow { awaitCancellation() } }
    withModel { model ->
      model.updateDraft("waiting for server permission")
      assertFalse(model.uiState.value.canSend)
      model.send()
      assertTrue(requests.isEmpty())
      observe = { flowOf(OrderResult.Error(OrderError.PermissionDenied)) }
      startChat(model)
      assertEquals(OrderError.PermissionDenied, model.uiState.value.error)
      assertFalse(model.uiState.value.canSend)
      model.send()
      assertTrue(requests.isEmpty())
    }
  }

  @Test
  fun invalidDraftsCannotBeSubmitted() = runTest {
    withModel(start = false) { model ->
      model.send()
      assertTrue(requests.isEmpty())
      startChat(model)
      assertTrue(model.uiState.value.context!!.partnerIsCommando)
      for (invalid in listOf("", " \n", "x".repeat(MAX_CHAT_TEXT_LENGTH + 1))) {
        model.updateDraft(invalid)
        model.send()
        assertFalse(model.uiState.value.canSend)
      }
      assertTrue(requests.isEmpty())
    }
  }

  @Test
  fun duplicateTapsKeepOneInFlightMessage() = runTest {
    withModel { model ->
      val gate = CompletableDeferred<OrderResult<Unit>>()
      write = { _, _ -> gate.await() }
      model.updateDraft("hello")
      model.send()
      sendDraft(model)
      model.updateDraft("must not replace the in-flight text")
      assertEquals("hello", model.uiState.value.draft)
      assertEquals(1, requests.size)
      assertTrue(model.uiState.value.sending)
      assertFalse(model.uiState.value.canEditDraft)
      gate.complete(OrderResult.Success(Unit))
      runCurrent()
      assertEquals("", model.uiState.value.draft)
      assertFalse(model.uiState.value.sending)
    }
  }

  @Test
  fun deliveredHistoryRemainsReadOnlyAfterRestart() = runTest {
    withModel { model ->
      history.value = OrderResult.Success(OrderChat(OrderStatus.DELIVERED, emptyList()))
      runCurrent()
      model.updateDraft("late")
      model.send()
      assertTrue(requests.isEmpty())
      assertFalse(model.uiState.value.canSend)
      model.stop()
      runCurrent()
      assertNull(model.uiState.value.context)
      assertNull(model.uiState.value.chat)
      startChat(model)
      assertEquals(OrderStatus.DELIVERED, model.uiState.value.chat!!.status)
    }
  }

  @Test
  fun definitiveRejectionUnlocksPreservedDraftAndUsesNewId() = runTest {
    for (error in
        listOf(OrderError.InvalidData, OrderError.PermissionDenied, OrderError.NotFound)) {
      val saved = SavedStateHandle()
      write = { _, _ -> OrderResult.Error(error) }
      withModel(saved) { model ->
        sendDraft(model, "rejected")
        assertEquals(error, model.uiState.value.sendError)
        assertEquals("rejected", model.uiState.value.draft)
        assertTrue(model.uiState.value.canEditDraft)
        model.updateDraft("corrected")
        assertNull(model.uiState.value.sendError)
      }
      val rejectedId = requests.last().first
      withModel(saved) { model ->
        assertTrue(model.uiState.value.canEditDraft)
        model.updateDraft("corrected")
        assertNull(model.uiState.value.sendError)
        write = { _, _ -> OrderResult.Success(Unit) }
        sendDraft(model)
        assertNotEquals(rejectedId, requests.last().first)
        assertEquals("corrected", requests.last().second)
        assertEquals("", model.uiState.value.draft)
      }
    }
  }

  @Test
  fun retryReusesIdAndRestorationCannotCrossAccounts() = runTest {
    val saved = SavedStateHandle()
    write = { _, _ -> OrderResult.Error(OrderError.Unknown) }
    withModel(saved) { model ->
      sendDraft(model, "keep me")
      assertEquals(OrderError.Unknown, model.uiState.value.sendError)
      assertFalse(model.uiState.value.canEditDraft)
      model.updateDraft("do not overwrite an uncertain send")
      assertEquals("keep me", model.uiState.value.draft)
    }
    withModel(saved) { model ->
      write = { _, _ -> OrderResult.Success(Unit) }
      sendDraft(model)
      assertEquals(requests.first(), requests.last())
      assertEquals("", model.uiState.value.draft)
      model.updateDraft("private draft")
    }
    user.value = "owner"
    withModel(saved) { model ->
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
      write = { _, _ -> OrderResult.Error(OrderError.Unknown) }
      sendDraft(model, "committed")
      val (id, text) = requests.single()
      publish(OrderStatus.ACCEPTED, ChatMessage(id, "owner", text, now))
      assertEquals(text, model.uiState.value.draft)
      publish(OrderStatus.DELIVERED, ChatMessage(id, "requester", text, now))
      assertEquals("", model.uiState.value.draft)
      assertNull(model.uiState.value.sendError)
      assertFalse(model.uiState.value.sending)
    }
  }

  @Test
  fun boundedOfflineLoadingRetryAndUnexpectedStreamEnd() = runTest {
    observe = { emptyFlow() }
    withModel { model ->
      assertEquals(OrderError.Unknown, model.uiState.value.error)
      observe = { flow { awaitCancellation() } }
      startChat(model)
      advanceTimeBy(OrderChatViewModel.RESPONSE_TIMEOUT_MS)
      runCurrent()
      assertEquals(OrderError.Unknown, model.uiState.value.error)
      assertFalse(model.uiState.value.loading)
      assertEquals(0, subscriptions)
      observe = { history }
      startChat(model)
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
      write = { _, _ -> OrderResult.Error(OrderError.NotAuthenticated) }
      sendDraft(model, "secret")
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
      load = { OrderResult.Error(error) }
      withModel { model -> assertEquals(error, model.uiState.value.error) }
    }
    load = { OrderResult.Success(order) }
    for ((error, expected) in
        listOf(
            TripError.NotFound to OrderError.NotFound,
            TripError.PermissionDenied to OrderError.PermissionDenied,
            TripError.Unknown to OrderError.Unknown,
        )) {
      tripResult = TripResult.Error(error)
      withModel { model -> assertEquals(expected, model.uiState.value.error) }
    }
    tripResult = TripResult.Success(trip)
    for (invalid in
        listOf(
            order.copy(status = OrderStatus.PENDING),
            order.copy(tripId = "wrong"),
            order.copy(requesterId = "outsider"),
        )) {
      load = { OrderResult.Success(invalid) }
      withModel { model -> assertEquals(OrderError.PermissionDenied, model.uiState.value.error) }
    }
    load = { throw IllegalStateException("offline") }
    withModel { model -> assertEquals(OrderError.Unknown, model.uiState.value.error) }
  }

  @Test
  fun stalledAndThrowingSendsKeepTheirIdAndBackgroundingCancelsThem() = runTest {
    withModel { model ->
      write = { _, _ -> awaitCancellation() }
      sendDraft(model, "pending")
      advanceTimeBy(OrderChatViewModel.RESPONSE_TIMEOUT_MS)
      runCurrent()
      assertEquals(OrderError.Unknown, model.uiState.value.sendError)
      assertEquals("pending", model.uiState.value.draft)
      write = { _, _ -> throw IllegalStateException("connection lost") }
      sendDraft(model)
      assertEquals(requests.first(), requests.last())
      write = { _, _ -> awaitCancellation() }
      sendDraft(model)
      model.stop()
      runCurrent()
      assertFalse(model.uiState.value.sending)
      assertEquals("pending", model.uiState.value.draft)
      startChat(model)
      history.value = OrderResult.Error(OrderError.PermissionDenied)
      runCurrent()
      assertEquals(OrderError.PermissionDenied, model.uiState.value.error)
      assertNull(model.uiState.value.chat)
      history.value = OrderResult.Success(OrderChat(OrderStatus.DELIVERED, emptyList()))
      startChat(model)
      write = { _, _ -> OrderResult.Success(Unit) }
      sendDraft(model)
      assertEquals(requests.first(), requests.last())
      assertEquals("", model.uiState.value.draft)
    }
  }

  @Test
  fun committedSnapshotBeforeFailedAcknowledgementStillClearsTheDraft() = runTest {
    withModel { model ->
      val acknowledgement = CompletableDeferred<OrderResult<Unit>>()
      write = { _, _ -> acknowledgement.await() }
      sendDraft(model, "acknowledged by stream")
      val (id, text) = requests.single()
      publish(OrderStatus.ACCEPTED, ChatMessage(id, "requester", text, now))
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
      assertEquals(OrderError.NotAuthenticated, model.uiState.value.error)
      assertNull(model.uiState.value.chat)
    }
    user.value = "requester"
    observe = { history }
    withModel { model ->
      write = { _, _ ->
        user.value = "outsider"
        OrderResult.Success(Unit)
      }
      sendDraft(model, "private")
      assertEquals(OrderError.NotAuthenticated, model.uiState.value.error)
      assertEquals("", model.uiState.value.draft)
    }
  }

  @Test
  fun permissionLossWhileSendingCannotRestoreMessagesAndUnknownIdsCannotClearDraft() = runTest {
    withModel { model ->
      val acknowledgement = CompletableDeferred<OrderResult<Unit>>()
      write = { _, _ -> acknowledgement.await() }
      sendDraft(model, "private draft")
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
        startChat(model)
        assertEquals(text, model.uiState.value.draft)
      }
    }
  }
}
