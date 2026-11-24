package com.commando.app.data.repository

// AI assistance: OpenAI Codex.
import com.commando.app.model.*
import java.time.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MeetupRepositoryTest {
  private val now = Instant.parse("2026-11-24T12:00:00Z")
  private val order = FakeChatOrder("requester", "owner", OrderStatus.ACCEPTED)
  private val orders = MutableStateFlow(mapOf("order" to order))
  private val uid = MutableStateFlow<String?>("owner")
  private val repo =
      FakeOrderChatRepository(FakeOrderChatStore(orders), uid, Clock.fixed(now, ZoneOffset.UTC))
  private val proposal = MeetupProposal("Library entrance", now.plusSeconds(3600))
  private val ok = OrderResult.Success(Unit)

  private suspend fun stored() =
      (repo.observeChat("order").first() as OrderResult.Success).data.messages.single().meetup!!

  @Test
  fun ownerProposesAndRequesterRespondsOnce() = runTest {
    assertEquals(ok, repo.proposeMeetup("order", "meeting", proposal))
    uid.value = "requester"
    assertEquals(proposal, stored())
    val outcomes =
        listOf(MeetupResponse.ACCEPTED, MeetupResponse.DECLINED)
            .map { response -> async { repo.respondToMeetup("order", "meeting", response) } }
            .awaitAll()
    assertEquals(1, outcomes.count { it is OrderResult.Success })
    val decided = stored()
    assertNotEquals(MeetupResponse.PENDING, decided.response)
    assertEquals(now, decided.respondedAt)
    assertEquals(ok, repo.respondToMeetup("order", "meeting", decided.response))
    uid.value = "owner"
    assertEquals(ok, repo.proposeMeetup("order", "meeting", proposal))
    assertEquals(decided, stored())
  }

  @Test
  fun authorizationAndTerminalStateAreCheckedBeforeNewWrites() = runTest {
    for (caller in listOf("requester", "outsider", null)) {
      uid.value = caller
      assertTrue(repo.proposeMeetup("order", "meeting", proposal) is OrderResult.Error)
    }
    uid.value = "owner"
    for (status in TripStatus.entries) {
      orders.value = mapOf("order" to order.copy(tripStatus = status))
      assertEquals(
          if (status.canReportDelay) ok else OrderResult.Error(OrderError.PermissionDenied),
          repo.proposeMeetup("order", status.name, proposal),
      )
    }
    orders.value = mapOf("order" to order)
    assertEquals(ok, repo.proposeMeetup("order", "meeting", proposal))
    assertEquals(
        OrderResult.Error(OrderError.PermissionDenied),
        repo.respondToMeetup("order", "meeting", MeetupResponse.ACCEPTED),
    )
    uid.value = "requester"
    orders.value = mapOf("order" to order.copy(status = OrderStatus.DELIVERED))
    assertEquals(
        OrderResult.Error(OrderError.PermissionDenied),
        repo.respondToMeetup("order", "meeting", MeetupResponse.ACCEPTED),
    )
  }

  @Test
  fun invalidRepliesAndMissingRecordsReturnTypedFailures() = runTest {
    uid.value = "requester"
    assertEquals(
        OrderResult.Error(OrderError.InvalidData),
        repo.respondToMeetup("", "meeting", MeetupResponse.ACCEPTED),
    )
    assertEquals(
        OrderResult.Error(OrderError.InvalidData),
        repo.respondToMeetup("order", "meeting", MeetupResponse.PENDING),
    )
    assertEquals(
        OrderResult.Error(OrderError.NotFound),
        repo.respondToMeetup("missing", "meeting", MeetupResponse.ACCEPTED),
    )
    assertEquals(
        OrderResult.Error(OrderError.NotFound),
        repo.respondToMeetup("order", "missing", MeetupResponse.ACCEPTED),
    )
    uid.value = null
    assertEquals(
        OrderResult.Error(OrderError.NotAuthenticated),
        repo.respondToMeetup("order", "meeting", MeetupResponse.ACCEPTED),
    )
  }

  @Test
  fun invalidProposalFieldsAndMessageIdentityCannotChangeHistory() = runTest {
    for (bad in
        listOf(
            proposal.copy(location = " "),
            proposal.copy(location = "x".repeat(MAX_CHAT_TEXT_LENGTH + 1)),
            proposal.copy(at = now),
            proposal.copy(response = MeetupResponse.ACCEPTED, respondedAt = now),
        )) {
      assertEquals(
          OrderResult.Error(OrderError.InvalidData),
          repo.proposeMeetup("order", "meeting", bad),
      )
    }
    assertEquals(ok, repo.proposeMeetup("order", "meeting", proposal))
    assertEquals(
        OrderResult.Error(OrderError.InvalidData),
        repo.proposeMeetup("order", "meeting", proposal.copy(location = "Other place")),
    )
    assertEquals(proposal, stored())
  }
}
