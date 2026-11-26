package com.commando.app.data.repository

// AI assistance: OpenAI Codex.
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderReadMetadataTest {
  @Test
  fun pendingOrderDetailIsRejectedBeforeReadingItsFields() = runTest {
    val firestore = mockk<FirebaseFirestore>()
    val doc = mockk<DocumentSnapshot>()
    every { firestore.collection("orders").document("o").get(Source.SERVER) } returns
        Tasks.forResult(doc)
    every { doc.metadata.isFromCache } returns false
    every { doc.metadata.hasPendingWrites() } returns true
    val repository = OrderRepositoryFirestore({ "requester" }, firestore)

    assertEquals(OrderResult.Error(OrderError.Unknown), repository.getOrderById("o"))
    verify(exactly = 0) { doc.exists() }
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

    assertEquals(OrderResult.Error(OrderError.NetworkError), repository.getMyOrders())
    verify(exactly = 0) { snapshot.documents }

    // Server confirmation of an empty result is safe to cache for the requester.
    every { snapshot.metadata.hasPendingWrites() } returns false
    every { snapshot.documents } returns emptyList()
    assertEquals(OrderResult.Success(emptyList<Any>()), repository.getMyOrders())
    verify(exactly = 1) { snapshot.documents }
  }

  @Test
  fun ownerIncomingOrdersRejectPendingWritesBeforeDecodingRows() = runTest {
    val firestore = mockk<FirebaseFirestore>()
    val trip = mockk<DocumentSnapshot>()
    val snapshot = mockk<QuerySnapshot>()
    every { firestore.collection("trips").document("trip").get(Source.SERVER) } returns
        Tasks.forResult(trip)
    every { trip.exists() } returns true
    every { trip.data } returns mapOf("ownerId" to "owner")
    every {
      firestore.collection("orders").whereEqualTo("tripId", "trip").get(Source.SERVER)
    } returns Tasks.forResult(snapshot)
    every { snapshot.metadata.isFromCache } returns false
    every { snapshot.metadata.hasPendingWrites() } returns true
    val repository = OrderRepositoryFirestore({ "owner" }, firestore)

    assertEquals(OrderResult.Error(OrderError.Unknown), repository.getOrdersForTrip("trip"))
    verify(exactly = 0) { snapshot.documents }
  }
}
