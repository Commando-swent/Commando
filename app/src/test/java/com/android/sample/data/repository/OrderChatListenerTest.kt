package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import io.mockk.*
import java.util.concurrent.Executor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Test

/** SDK registration cleanup is observable even when no server snapshot has arrived. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderChatListenerTest {
  @Test
  fun cancellationRemovesOrderMessageAndAuthListeners() = runTest {
    mockkStatic(FirebaseAuth::class)
    try {
      val auth = mockk<FirebaseAuth>(relaxed = true)
      val firestore = mockk<FirebaseFirestore>(relaxed = true)
      val orderRef = mockk<DocumentReference>(relaxed = true)
      val messages = mockk<CollectionReference>(relaxed = true)
      val orderListener = mockk<ListenerRegistration>(relaxed = true)
      val messageListener = mockk<ListenerRegistration>(relaxed = true)
      val sessions = mutableListOf<FirebaseAuth.AuthStateListener>()
      every { FirebaseAuth.getInstance(any()) } returns auth
      every { auth.currentUser } returns mockk { every { uid } returns "requester" }
      every { firestore.collection("orders").document("order") } returns orderRef
      every { orderRef.collection("messages") } returns messages
      every { messages.orderBy(any<String>(), any()) } returns messages
      every { messages.orderBy(any<FieldPath>(), any()) } returns messages
      every { messages.limit(any()) } returns messages
      every {
        orderRef.addSnapshotListener(
            any<Executor>(),
            MetadataChanges.INCLUDE,
            any<EventListener<DocumentSnapshot>>(),
        )
      } returns orderListener
      every {
        messages.addSnapshotListener(
            any<Executor>(),
            MetadataChanges.INCLUDE,
            any<EventListener<QuerySnapshot>>(),
        )
      } returns messageListener
      every { auth.addAuthStateListener(capture(sessions)) } just Runs
      val job = backgroundScope.launch {
        OrderChatRepositoryFirestore({ "requester" }, firestore).observeChat("order").collect()
      }
      runCurrent()
      verify(exactly = 2) { auth.addAuthStateListener(any()) }
      job.cancelAndJoin()
      verify(exactly = 1) { orderListener.remove() }
      verify(exactly = 1) { messageListener.remove() }
      sessions.forEach { listener ->
        verify(exactly = 1) { auth.removeAuthStateListener(listener) }
      }
    } finally {
      unmockkStatic(FirebaseAuth::class)
    }
  }
}
