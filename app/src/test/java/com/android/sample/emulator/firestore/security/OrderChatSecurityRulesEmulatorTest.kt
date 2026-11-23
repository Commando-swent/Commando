package com.android.sample.emulator.firestore.security

// AI assistance: OpenAI Codex.
import android.os.Looper
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.commando.app.model.OrderStatus
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Two-account SDK journeys and malicious writes against production rules. */
@RunWith(RobolectricTestRunner::class)
class OrderChatSecurityRulesEmulatorTest {
  private val sent = OrderResult.Success(Unit)

  @Test
  fun delayNoticeUsesPersistedOwnerAndSurvivesTerminalRetry() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val id = seed(owner, requester)
      val ownerRepo = repo(owner)
      val requesterRepo = repo(requester)
      assertFailure(OrderError.PermissionDenied, requesterRepo.sendDelayNotice(id, "forged", 10))
      val data =
          mapOf(
              "senderId" to requester.auth.currentUser!!.uid,
              "text" to "",
              "createdAt" to FieldValue.serverTimestamp(),
              "delayMinutes" to 10,
          )
      val messages = requester.firestore.collection("orders").document(id).collection("messages")
      assertPermissionDenied { messages.document("raw-forged").set(data).await() }
      val ownerMessages = owner.firestore.collection("orders").document(id).collection("messages")
      for (invalid in listOf(0, MAX_CHAT_DELAY_MINUTES + 1, 1.5, "10")) {
        assertPermissionDenied {
          ownerMessages
              .document(UUID.randomUUID().toString())
              .set(
                  data +
                      mapOf("senderId" to owner.auth.currentUser!!.uid, "delayMinutes" to invalid)
              )
              .await()
        }
      }
      listOf(
              async { ownerRepo.sendDelayNotice(id, "delay", 10) },
              async { ownerRepo.sendDelayNotice(id, "delay", 10) },
          )
          .awaitAll()
          .forEach { assertEquals(sent, it) }
      val notice =
          (requesterRepo.observeChat(id).first() as OrderResult.Success).data.messages.single()
      assertEquals(10, notice.delayMinutes)
      assertEquals("", notice.text)
      assertFailure(OrderError.InvalidData, ownerRepo.sendDelayNotice(id, "delay", 11))
      for (invalid in listOf(0, MAX_CHAT_DELAY_MINUTES + 1, "10")) {
        createDocumentAsOwner(
            "orders/$id/messages",
            "corrupt-delay",
            mapOf(
                "senderId" to owner.auth.currentUser!!.uid,
                "text" to "",
                "createdAt" to Timestamp.now(),
                "delayMinutes" to invalid,
            ),
        )
        assertFailure(OrderError.InvalidData, requesterRepo.observeChat(id).first())
        deleteDocumentAsOwner("orders/$id/messages/corrupt-delay")
      }
      assertPermissionDenied {
        ownerMessages
            .document("mixed")
            .set(
                data +
                    mapOf(
                        "senderId" to owner.auth.currentUser!!.uid,
                        "text" to "ambiguous",
                    )
            )
            .await()
      }

      updateDocumentAsOwner("orders/$id", mapOf("status" to OrderStatus.DELIVERED.name))
      assertEquals(sent, ownerRepo.sendDelayNotice(id, "delay", 10))
      assertFailure(OrderError.PermissionDenied, ownerRepo.sendDelayNotice(id, "late", 1))
      assertPermissionDenied { ownerMessages.document("delay").update("delayMinutes", 20).await() }
      assertPermissionDenied { ownerMessages.document("delay").delete().await() }
    }
  }

  @Test
  fun liveConversationRetriesTerminalHistoryAndEndedSession() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val id = seed(owner, requester)
      val ownerRepo = repo(owner)
      val requesterRepo = repo(requester)
      assertEquals(
          OrderResult.Success(OrderChat(OrderStatus.ACCEPTED, emptyList())),
          ownerRepo.observeChat(id).first(),
      )
      val received =
          async(Dispatchers.IO) {
            withTimeout(20_000) {
              ownerRepo.observeChat(id).first {
                it is OrderResult.Success && it.data.messages.size == 2
              }
            }
          }
      val messageId = UUID.randomUUID().toString()
      listOf(
              async { requesterRepo.sendMessage(id, messageId, "Hello") },
              async { requesterRepo.sendMessage(id, messageId, "Hello") },
          )
          .awaitAll()
          .forEach { assertEquals(sent, it) }
      assertEquals(sent, ownerRepo.sendMessage(id, "reply", "Hi"))
      val chat = (received.await() as OrderResult.Success).data
      assertEquals(listOf("Hello", "Hi"), chat.messages.map { it.text })
      assertEquals(
          listOf(requester.auth.currentUser!!.uid, owner.auth.currentUser!!.uid),
          chat.messages.map { it.senderId },
      )
      assertFailure(OrderError.InvalidData, requesterRepo.sendMessage(id, messageId, "changed"))
      assertFailure(OrderError.InvalidData, ownerRepo.sendMessage(id, messageId, "Hello"))
      val terminal =
          async(Dispatchers.IO) {
            withTimeout(20_000) {
              requesterRepo.observeChat(id).first {
                it is OrderResult.Success && it.data.status == OrderStatus.DELIVERED
              }
            }
          }
      updateDocumentAsOwner("orders/$id", mapOf("status" to OrderStatus.DELIVERED.name))
      assertEquals(2, (terminal.await() as OrderResult.Success).data.messages.size)
      assertEquals(sent, requesterRepo.sendMessage(id, messageId, "Hello"))
      assertFailure(OrderError.PermissionDenied, ownerRepo.sendMessage(id, "late", "Hi"))
      assertEquals(
          chat.messages,
          (repo(requester).observeChat(id).first() as OrderResult.Success).data.messages,
      )
      val listening = CompletableDeferred<Unit>()
      val ended =
          async(Dispatchers.IO) {
            withTimeout(20_000) {
              ownerRepo
                  .observeChat(id)
                  .onEach { listening.complete(Unit) }
                  .first { it is OrderResult.Error }
            }
          }
      listening.await()
      owner.auth.signOut()
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals(OrderResult.Error(OrderError.NotAuthenticated), ended.await())
      assertFailure(OrderError.NotAuthenticated, ownerRepo.sendMessage(id, "late", "text"))
      for (user in listOf(chat.messages.last().senderId, "")) {
        val expired = OrderChatRepositoryFirestore({ user }, owner.firestore)
        assertFailure(OrderError.NotAuthenticated, expired.sendMessage(id, "late", "text"))
        assertFailure(OrderError.NotAuthenticated, expired.observeChat(id).first())
      }
    }
  }

  @Test
  fun statusesPermissionsValidationAndImmutableMessages() = runTest {
    withClients("owner" to true, "requester" to true, "outsider" to true, "signedOut" to false) {
        (owner, requester, outsider, signedOut) ->
      val id = seed(owner, requester)
      val ref = requester.firestore.collection("orders").document(id).collection("messages")
      val data =
          mapOf(
              "senderId" to requester.auth.currentUser!!.uid,
              "text" to "text",
              "createdAt" to FieldValue.serverTimestamp(),
          )
      for (status in OrderStatus.entries) {
        updateDocumentAsOwner("orders/$id", mapOf("status" to status.name))
        val expected =
            if (status.canSendChat) sent else OrderResult.Error(OrderError.PermissionDenied)
        assertEquals(expected, repo(requester).sendMessage(id, status.name, "text"))
        if (!status.canSendChat)
            assertPermissionDenied { ref.document("direct-${status.name}").set(data).await() }
      }
      updateDocumentAsOwner("orders/$id", mapOf("status" to OrderStatus.ACCEPTED.name))
      for (client in listOf(outsider, signedOut)) {
        val messages = client.firestore.collection("orders").document(id).collection("messages")
        assertPermissionDenied { messages.limit(CHAT_WINDOW_SIZE).get(Source.SERVER).await() }
        assertPermissionDenied { messages.document("forged").set(data).await() }
        val expectedError =
            if (client == outsider) OrderError.PermissionDenied else OrderError.NotAuthenticated
        assertFailure(expectedError, repo(client).observeChat(id).first())
      }
      for (invalid in
          listOf(
              data + ("senderId" to owner.auth.currentUser!!.uid),
              data + ("createdAt" to Timestamp(0, 0)),
              data + ("extra" to true),
              data - "text",
              data + ("text" to 1),
          )) assertPermissionDenied {
        ref.document(UUID.randomUUID().toString()).set(invalid).await()
      }
      for (text in
          listOf(
              "",
              " \t\n\u00a0\u001c",
              "x".repeat(MAX_CHAT_TEXT_LENGTH + 1),
              "😀".repeat(MAX_CHAT_TEXT_LENGTH / 2 + 1),
          )) {
        assertFailure(OrderError.InvalidData, repo(requester).sendMessage(id, "invalid", text))
        assertPermissionDenied {
          ref.document(UUID.randomUUID().toString()).set(data + ("text" to text)).await()
        }
      }
      assertEquals(
          sent,
          repo(requester).sendMessage(id, "boundary", "😀".repeat(MAX_CHAT_TEXT_LENGTH / 2)),
      )
      for (client in listOf(owner, requester)) {
        val message =
            client.firestore
                .collection("orders")
                .document(id)
                .collection("messages")
                .document("boundary")
        assertTrue(message.get(Source.SERVER).await().exists())
        assertPermissionDenied { message.update("text", "edited").await() }
        assertPermissionDenied { message.delete().await() }
      }
      updateDocumentAsOwner("orders/$id", mapOf("status" to OrderStatus.READY_FOR_HANDOFF.name))
      assertPermissionDenied {
        requester.firestore
            .batch()
            .apply {
              update(
                  requester.firestore.collection("orders").document(id),
                  "status",
                  OrderStatus.DELIVERED.name,
              )
              set(ref.document("terminal-batch"), data)
            }
            .commit()
            .await()
      }
      for (field in listOf("senderId", "text", "createdAt")) {
        createDocumentAsOwner(
            "orders/$id/messages",
            "corrupt",
            data + ("createdAt" to Timestamp.now()) + (field to null),
        )
        assertFailure(OrderError.InvalidData, repo(requester).observeChat(id).first())
        deleteDocumentAsOwner("orders/$id/messages/corrupt")
      }
      assertPermissionDenied { ref.get(Source.SERVER).await() }
      assertPermissionDenied { ref.limit(CHAT_WINDOW_SIZE + 1).get(Source.SERVER).await() }
      for (invalidId in listOf("", "path/id")) {
        assertFailure(OrderError.InvalidData, repo(requester).sendMessage(invalidId, "id", "text"))
        assertFailure(OrderError.InvalidData, repo(requester).sendMessage(id, invalidId, "text"))
        assertFailure(OrderError.InvalidData, repo(requester).observeChat(invalidId).first())
      }
      assertFailure(
          OrderError.NotFound,
          repo(requester).sendMessage(UUID.randomUUID().toString(), "id", "text"),
      )
    }
  }

  private fun repo(client: EmulatorClient) =
      OrderChatRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)

  private fun seed(owner: EmulatorClient, requester: EmulatorClient): String {
    val id = UUID.randomUUID().toString()
    createDocumentAsOwner(
        "trips",
        id,
        mapOf("ownerId" to owner.auth.currentUser!!.uid, "status" to "IN_PROGRESS"),
    )
    createDocumentAsOwner(
        "orders",
        id,
        mapOf(
            "tripId" to id,
            "requesterId" to requester.auth.currentUser!!.uid,
            "status" to OrderStatus.ACCEPTED.name,
        ),
    )
    return id
  }

  private fun assertFailure(error: OrderError, result: OrderResult<*>) =
      assertEquals(OrderResult.Error(error), result)
}
