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
    val scope = backgroundScope
    val saved = SavedStateHandle()
    var actions = MeetupActions(order.id, repository, chat, saved, scope)
    result = OrderResult.Error(OrderError.Unknown)
    actions.propose("Entrance", now.plusSeconds(3600))
    runCurrent()
    assertTrue(actions.hasPending)
    assertFalse(chat.value.canEditDraft)
    actions.stop()
    actions = MeetupActions(order.id, repository, chat, saved, scope)
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
    actions.clear()
    assertFalse(actions.hasPending)
    actions.retry()
    runCurrent()
    assertEquals(1, ids.size)
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
}
