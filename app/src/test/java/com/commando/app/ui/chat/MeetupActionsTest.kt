package com.commando.app.ui.chat

// AI assistance: OpenAI Codex.
import androidx.lifecycle.SavedStateHandle
import com.commando.app.data.repository.*
import com.commando.app.model.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeetupActionsTest {
  private val now = Instant.now()
  private val fixture = chatPreviewState(empty = true)
  private val order = requireNotNull(fixture.context).order
  private val trip = requireNotNull(fixture.context).trip
  private val uid = MutableStateFlow<String?>(trip.ownerId)
  private val chat =
      MutableStateFlow(
          fixture.copy(context = ChatContext(order, trip, trip.ownerId), draft = "typed message")
      )
  private val trusted = FakeChatOrder(order.requesterId, trip.ownerId, order.status)
  private val store = FakeOrderChatStore(MutableStateFlow(mapOf(order.id to trusted)))
  private val fake = FakeOrderChatRepository(store, uid)
  private val ids = mutableListOf<String>()
  private var result: OrderResult<Unit>? = null
  private val repository =
      object : OrderChatRepository by fake {
        override suspend fun respondToMeetup(
            orderId: String,
            messageId: String,
            response: MeetupResponse,
        ): OrderResult<Unit> {
          fake.respondToMeetup(orderId, messageId, response)
          throw java.io.IOException("Acknowledgement lost")
        }

        override suspend fun proposeMeetup(
            orderId: String,
            messageId: String,
            proposal: MeetupProposal,
        ): OrderResult<Unit> {
          ids += messageId
          if (result != null && result != OrderResult.Error(OrderError.Unknown))
              return checkNotNull(result)
          val committed = fake.proposeMeetup(orderId, messageId, proposal)
          if (result == OrderResult.Error(OrderError.Unknown))
              throw java.io.IOException("Acknowledgement lost")
          return result ?: committed
        }
      }

  private suspend fun refresh() {
    chat.value =
        chat.value.copy(chat = (fake.observeChat(order.id).first() as OrderResult.Success).data)
  }

  @Test
  fun uncertainProposalRestoresSameIdentityAndReconcilesWithoutLosingText() = runTest {
    val saved = SavedStateHandle()
    var actions = MeetupActions(order.id, repository, chat, saved, backgroundScope)
    result = OrderResult.Error(OrderError.Unknown)
    actions.propose("Entrance", now.plusSeconds(3600))
    actions.retry()
    runCurrent()
    assertTrue(actions.hasPending)
    assertFalse(chat.value.canEditDraft)
    val pending = chat.value
    for (blocked in
        listOf(
            pending.copy(context = null),
            pending.copy(loading = true),
            pending.copy(online = false),
            pending.copy(cached = true),
            pending.copy(error = OrderError.PermissionDenied),
        )) {
      chat.value = blocked
      actions.retry()
    }
    chat.value = pending
    actions.respond("meeting", MeetupResponse.PENDING)
    actions.stop()
    actions = MeetupActions(order.id, repository, chat, saved, backgroundScope)
    actions.retry()
    runCurrent()
    assertEquals(2, ids.size)
    assertEquals(ids.first(), ids.last())
    refresh()
    actions.reconcile()
    assertFalse(actions.hasPending)
    assertNull(actions.state.value.error)
    assertEquals("typed message", chat.value.draft)
    assertTrue(chat.value.canEditDraft)
    actions.respond("meeting", MeetupResponse.PENDING)
  }

  @Test
  fun definitiveFailureAllowsANewProposalAndUsesAFreshIdentity() = runTest {
    val actions = MeetupActions(order.id, repository, chat, SavedStateHandle(), backgroundScope)
    result = OrderResult.Error(OrderError.PermissionDenied)
    actions.propose("Entrance", now.plusSeconds(3600))
    runCurrent()
    assertFalse(actions.hasPending)
    assertTrue(chat.value.canEditDraft)
    chat.value = chat.value.copy(sendError = OrderError.Unknown)
    actions.retry()
    runCurrent()
    assertEquals(1, ids.size)
    chat.value = chat.value.copy(sendError = null)
    result = null
    actions.retry()
    runCurrent()
    assertNotEquals(ids.first(), ids.last())
    assertEquals("typed message", chat.value.draft)
  }

  @Test
  fun requesterCanRespondAndEndedSessionClearsSavedAction() = runTest {
    fake.proposeMeetup(order.id, "meeting", MeetupProposal("Entrance", now.plusSeconds(3600)))
    uid.value = order.requesterId
    chat.value = chat.value.copy(context = ChatContext(order, trip, order.requesterId))
    refresh()
    val actions = MeetupActions(order.id, repository, chat, SavedStateHandle(), backgroundScope)
    actions.respond("meeting", MeetupResponse.ACCEPTED)
    runCurrent()
    refresh()
    actions.reconcile()
    assertEquals(MeetupResponse.ACCEPTED, chat.value.chat!!.messages.single().meetup!!.response)
    assertFalse(actions.hasPending)
    uid.value = trip.ownerId
    chat.value = chat.value.copy(context = ChatContext(order, trip, trip.ownerId))
    result = OrderResult.Error(OrderError.Unknown)
    actions.propose("Entrance", now.plusSeconds(7200))
    runCurrent()
    assertTrue(actions.hasPending)
    actions.retry()
    actions.clear()
    chat.value = chat.value.copy(context = null)
    assertFalse(actions.hasPending)
    actions.retry()
    runCurrent()
    assertEquals(2, ids.size)
  }

  @Test
  fun invalidRoleAndTerminalConversationCannotSubmitNewActions() = runTest {
    val actions = MeetupActions(order.id, repository, chat, SavedStateHandle(), backgroundScope)
    chat.value = chat.value.copy(context = ChatContext(order, trip, order.requesterId))
    actions.propose("Entrance", now.plusSeconds(3600))
    runCurrent()
    chat.value =
        chat.value.copy(
            context = ChatContext(order, trip, trip.ownerId),
            chat = OrderChat(OrderStatus.DELIVERED, emptyList()),
        )
    actions.propose("Entrance", now.plusSeconds(3600))
    runCurrent()
    assertTrue(ids.isEmpty())
  }

  @Test
  fun offlineOrCachedChatCannotCreateOrRespondToAMeetup() = runTest {
    val actions = MeetupActions(order.id, repository, chat, SavedStateHandle(), backgroundScope)
    for (blocked in listOf(chat.value.copy(online = false), chat.value.copy(cached = true))) {
      chat.value = blocked
      actions.propose("Entrance", now.plusSeconds(3600))
      actions.respond("meeting", MeetupResponse.ACCEPTED)
      runCurrent()
      assertTrue(ids.isEmpty())
      assertFalse(actions.hasPending)
      assertNull(actions.state.value.error)
    }
  }
}
