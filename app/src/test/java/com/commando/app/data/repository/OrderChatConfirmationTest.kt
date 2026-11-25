package com.commando.app.data.repository

// AI assistance: OpenAI Codex (#390).
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import io.mockk.*
import java.util.concurrent.Executor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Metadata loss invalidates sending without exposing unconfirmed SDK message contents. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderChatConfirmationTest {
  private val orderCallback = slot<EventListener<DocumentSnapshot>>()
  private val messageCallback = slot<EventListener<QuerySnapshot>>()
  private val snapshot = mockk<DocumentSnapshot>(relaxed = true)
  private val history = mockk<QuerySnapshot>(relaxed = true)
  private var orderCached = true
  private var messagesCached = true
  private var pending = false
  private var orderPending = false
  private lateinit var repository: OrderChatRepository

  @Before
  fun setup() {
    mockkStatic(FirebaseAuth::class)
    val auth = mockk<FirebaseAuth>(relaxed = true)
    val firestore = mockk<FirebaseFirestore>(relaxed = true)
    val ref = mockk<DocumentReference>(relaxed = true)
    val messages = mockk<CollectionReference>(relaxed = true)
    every { FirebaseAuth.getInstance(any()) } returns auth
    every { auth.currentUser } returns mockk { every { uid } returns "requester" }
    every { firestore.collection("orders").document("order") } returns ref
    every { ref.collection("messages") } returns messages
    every { messages.orderBy(any<String>(), any()) } returns messages
    every { messages.orderBy(any<FieldPath>(), any()) } returns messages
    every { messages.limit(any()) } returns messages
    every {
      ref.addSnapshotListener(
          any<Executor>(),
          MetadataChanges.INCLUDE,
          capture(orderCallback),
      )
    } returns mockk(relaxed = true)
    every {
      messages.addSnapshotListener(
          any<Executor>(),
          MetadataChanges.INCLUDE,
          capture(messageCallback),
      )
    } returns mockk(relaxed = true)
    every { snapshot.exists() } returns true
    every { snapshot.getString("status") } returns "ACCEPTED"
    val orderMetadata = mockk<SnapshotMetadata>()
    val messagesMetadata = mockk<SnapshotMetadata>()
    every { snapshot.metadata } returns orderMetadata
    every { history.metadata } returns messagesMetadata
    every { orderMetadata.isFromCache } answers { orderCached }
    every { orderMetadata.hasPendingWrites() } answers { orderPending }
    every { messagesMetadata.isFromCache } answers { messagesCached }
    every { messagesMetadata.hasPendingWrites() } answers { pending }
    every { history.documents } returns emptyList()
    repository = OrderChatRepositoryFirestore({ "requester" }, firestore)
  }

  @After fun teardown() = unmockkStatic(FirebaseAuth::class)

  private suspend fun TestScope.observe(
      block: suspend TestScope.(MutableList<OrderResult<com.commando.app.model.OrderChat>>) -> Unit
  ) {
    val received = mutableListOf<OrderResult<com.commando.app.model.OrderChat>>()
    val job = backgroundScope.launch { repository.observeChat("order").collect { received += it } }
    runCurrent()
    try {
      block(received)
    } finally {
      job.cancelAndJoin()
    }
  }

  @Test
  fun bothSnapshotsMustBeServerConfirmed() = runTest {
    observe { received ->
      orderCallback.captured.onEvent(snapshot, null)
      messageCallback.captured.onEvent(history, null)
      runCurrent()
      assertFalse((received.last() as OrderResult.Success).data.serverConfirmed)
      orderCached = false
      orderCallback.captured.onEvent(snapshot, null)
      runCurrent()
      assertFalse((received.last() as OrderResult.Success).data.serverConfirmed)
      messagesCached = false
      messageCallback.captured.onEvent(history, null)
      runCurrent()
      assertTrue((received.last() as OrderResult.Success).data.serverConfirmed)
    }
  }

  @Test
  fun pendingOrCachedSnapshotsRevokeConfirmation() = runTest {
    observe { received ->
      orderCached = false
      messagesCached = false
      orderCallback.captured.onEvent(snapshot, null)
      messageCallback.captured.onEvent(history, null)
      runCurrent()
      assertTrue((received.last() as OrderResult.Success).data.serverConfirmed)
      orderPending = true
      orderCallback.captured.onEvent(snapshot, null)
      runCurrent()
      assertFalse((received.last() as OrderResult.Success).data.serverConfirmed)
      orderPending = false
      pending = true
      messageCallback.captured.onEvent(history, null)
      runCurrent()
      assertFalse((received.last() as OrderResult.Success).data.serverConfirmed)
      assertTrue((received.last() as OrderResult.Success).data.messages.isEmpty())
    }
  }
}
