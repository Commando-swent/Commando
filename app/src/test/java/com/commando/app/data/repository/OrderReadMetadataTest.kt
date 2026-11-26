package com.commando.app.data.repository

// AI assistance: OpenAI Codex.
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SnapshotMetadata
import com.google.firebase.firestore.Source
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OrderReadMetadataTest {
  @Test
  fun pendingOrderDetailIsRejectedBeforeReadingItsFields() =
      kotlinx.coroutines.test.runTest {
        val firestore = mockk<com.google.firebase.firestore.FirebaseFirestore>()
        val doc = mockk<com.google.firebase.firestore.DocumentSnapshot>()
        every {
          firestore
              .collection("orders")
              .document("o")
              .get(com.google.firebase.firestore.Source.SERVER)
        } returns com.google.android.gms.tasks.Tasks.forResult(doc)
        every { doc.metadata.isFromCache } returns false
        every { doc.metadata.hasPendingWrites() } returns true
        val result = runCatching { FirebaseOrderFirestoreDataSource(firestore).get("o") }
        assertEquals(
            FirebaseFirestoreException.Code.UNAVAILABLE,
            (result.exceptionOrNull() as FirebaseFirestoreException).code,
        )
        io.mockk.verify(exactly = 0) { doc.exists() }
      }

  @Test
  fun requesterHistoryDoesNotDecodeRowsFromAQueryWithPendingWrites() = runTest {
    val firestore = mockk<FirebaseFirestore>()
    val snapshot = mockk<QuerySnapshot>()
    every {
      firestore.collection("orders").whereEqualTo("requesterId", "requester").get(Source.SERVER)
    } returns Tasks.forResult(snapshot)
    every { snapshot.metadata.isFromCache } returns false
    every { snapshot.metadata.hasPendingWrites() } returns true
    val repository = OrderRepositoryFirestore({ "requester" }, firestore)

    assertEquals(OrderResult.Error(OrderError.Unknown), repository.getMyOrders())
    verify(exactly = 0) { snapshot.documents }

    // Server confirmation of an empty result is safe to cache for the requester.
    every { snapshot.metadata.hasPendingWrites() } returns false
    every { snapshot.documents } returns emptyList()
    assertEquals(OrderResult.Success(emptyList<Any>()), repository.getMyOrders())
    verify(exactly = 1) { snapshot.documents }
  }

  @Test
  fun onlyServerDataWithoutPendingWritesCanEnterTheOrderCache() {
    val metadata = mockk<SnapshotMetadata>()
    for (cached in listOf(false, true)) for (pending in listOf(false, true)) {
      every { metadata.isFromCache } returns cached
      every { metadata.hasPendingWrites() } returns pending
      if (cached || pending) {
        val failure =
            assertThrows(FirebaseFirestoreException::class.java) {
              metadata.requireConfirmedOrderRead()
            }
        assertEquals(FirebaseFirestoreException.Code.UNAVAILABLE, failure.code)
      } else metadata.requireConfirmedOrderRead()
    }
  }
}
