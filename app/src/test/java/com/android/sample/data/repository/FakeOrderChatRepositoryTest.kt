package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.*
import com.commando.app.model.OrderStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FakeOrderChatRepositoryTest {
  private val sent = OrderResult.Success(Unit)
  private val uid = MutableStateFlow<String?>("requester")
  private val orders =
      MutableStateFlow(mapOf("order" to FakeChatOrder("requester", "owner", OrderStatus.ACCEPTED)))
  private val store = FakeOrderChatStore(orders)
  private val repo = FakeOrderChatRepository(store, uid, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))

  @Test
  fun validationAuthorizationAndRetryParity() = runTest {
    assertTrue("😀".repeat(MAX_CHAT_TEXT_LENGTH / 2).isValidChatText())
    for (text in
        listOf(
            "",
            " \n\t\u00a0\u001c",
            "x".repeat(MAX_CHAT_TEXT_LENGTH + 1),
            "😀".repeat(MAX_CHAT_TEXT_LENGTH / 2 + 1),
        )) {
      assertFalse(text.isValidChatText())
      assertEquals(OrderResult.Error(OrderError.InvalidData), repo.sendMessage("order", "id", text))
    }
    for (id in listOf("", ".", "..", "path/id")) {
      assertEquals(OrderResult.Error(OrderError.InvalidData), repo.sendMessage(id, "id", "text"))
      assertEquals(OrderResult.Error(OrderError.InvalidData), repo.sendMessage("order", id, "text"))
      assertEquals(OrderResult.Error(OrderError.InvalidData), repo.observeChat(id).first())
    }
    assertEquals(OrderResult.Error(OrderError.NotFound), repo.sendMessage("missing", "id", "text"))
    assertEquals(OrderResult.Error(OrderError.NotFound), repo.observeChat("missing").first())
    assertEquals(sent, repo.sendMessage("order", "id", "text"))
    assertFailure(OrderError.InvalidData, repo.sendMessage("order", "id", "changed"))
    for (status in OrderStatus.entries) {
      orders.value = mapOf("order" to orders.value.getValue("order").copy(status = status))
      assertEquals(
          if (status == OrderStatus.PENDING) OrderResult.Error(OrderError.PermissionDenied)
          else sent,
          repo.sendMessage("order", "id", "text"),
      )
      val expected =
          if (status.canSendChat) sent else OrderResult.Error(OrderError.PermissionDenied)
      assertEquals(expected, repo.sendMessage("order", status.name, "text"))
    }
    uid.value = "owner"
    assertEquals(OrderResult.Error(OrderError.InvalidData), repo.sendMessage("order", "id", "text"))
    uid.value = "outsider"
    assertFailure(OrderError.PermissionDenied, repo.sendMessage("order", "new", "text"))
    assertEquals(OrderResult.Error(OrderError.PermissionDenied), repo.observeChat("order").first())
    for (user in listOf(null, "")) {
      uid.value = user
      assertFailure(OrderError.NotAuthenticated, repo.sendMessage("order", "new", "text"))
      assertFailure(OrderError.NotAuthenticated, repo.observeChat("order").first())
    }
  }

  @Test
  fun sharedLiveWindowStableOrderingAndSessionTermination() = runTest {
    val owner = FakeOrderChatRepository(store, MutableStateFlow("owner"))
    assertEquals(
        OrderResult.Success(OrderChat(OrderStatus.ACCEPTED, emptyList())),
        owner.observeChat("order").first(),
    )
    val ended = async { repo.observeChat("order").first { it is OrderResult.Error } }
    testScheduler.runCurrent()
    for (index in 0..CHAT_WINDOW_SIZE.toInt()) {
      assertEquals(
          sent,
          repo.sendMessage("order", index.toString().padStart(3, '0'), "text"),
      )
    }
    val chat = (owner.observeChat("order").first() as OrderResult.Success).data
    assertEquals(CHAT_WINDOW_SIZE.toInt(), chat.messages.size)
    assertEquals("001", chat.messages.first().id)
    uid.value = "owner"
    assertEquals(OrderResult.Error(OrderError.NotAuthenticated), ended.await())
    orders.value =
        mapOf("order" to orders.value.getValue("order").copy(status = OrderStatus.PENDING))
    assertEquals(OrderResult.Error(OrderError.PermissionDenied), owner.observeChat("order").first())
  }

  @Test
  fun onlyOwnerCanSendCanonicalDelayWithIdempotentTerminalRetry() = runTest {
    assertFailure(OrderError.PermissionDenied, repo.sendDelayNotice("order", "delay", 10))
    val owner = FakeOrderChatRepository(store, MutableStateFlow("owner"))
    for (minutes in listOf(0, -1, MAX_CHAT_DELAY_MINUTES + 1)) {
      assertFailure(OrderError.InvalidData, owner.sendDelayNotice("order", "delay", minutes))
    }
    assertFalse(isValidChatPayload("ambiguous", 10))
    assertEquals(sent, owner.sendDelayNotice("order", "delay", MAX_CHAT_DELAY_MINUTES))
    assertFailure(OrderError.InvalidData, owner.sendDelayNotice("order", "delay", 1))
    assertFailure(OrderError.InvalidData, owner.sendMessage("order", "delay", "text"))
    val message = (repo.observeChat("order").first() as OrderResult.Success).data.messages.single()
    assertEquals(MAX_CHAT_DELAY_MINUTES, message.delayMinutes)
    assertEquals("", message.text)
    for (status in OrderStatus.entries.filter { it != OrderStatus.PENDING }) {
      orders.value = mapOf("order" to orders.value.getValue("order").copy(status = status))
      assertEquals(sent, owner.sendDelayNotice("order", "delay", MAX_CHAT_DELAY_MINUTES))
      assertEquals(
          if (status.canSendChat) sent else OrderResult.Error(OrderError.PermissionDenied),
          owner.sendDelayNotice("order", status.name, 1),
      )
    }
  }

  private fun assertFailure(error: OrderError, result: OrderResult<*>) =
      assertEquals(OrderResult.Error(error), result)
}
