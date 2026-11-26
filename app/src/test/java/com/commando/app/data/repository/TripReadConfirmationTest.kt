package com.commando.app.data.repository

// AI assistance: OpenAI Codex (#391 / #392).
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SnapshotMetadata
import com.google.firebase.firestore.Source
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Pending local writes must never become confirmed trip details, even with a SERVER read. */
class TripReadConfirmationTest {
  private val firestore = mockk<FirebaseFirestore>()
  private val collection = mockk<CollectionReference>()
  private val reference = mockk<DocumentReference>()
  private val snapshot = mockk<DocumentSnapshot>()
  private val metadata = mockk<SnapshotMetadata>()

  private fun source(
      cached: Boolean = false,
      pending: Boolean = false,
  ): FirebaseTripFirestoreDataSource {
    every { firestore.collection("trips") } returns collection
    every { collection.document("trip") } returns reference
    every { reference.get(Source.SERVER) } returns Tasks.forResult(snapshot)
    every { snapshot.metadata } returns metadata
    every { metadata.isFromCache } returns cached
    every { metadata.hasPendingWrites() } returns pending
    return FirebaseTripFirestoreDataSource(firestore)
  }

  @Test
  fun confirmedDocumentIsReturned() = runTest {
    val source = source()
    every { snapshot.exists() } returns true
    every { snapshot.id } returns "trip"
    every { snapshot.data } returns mapOf("ownerId" to "owner")

    assertEquals(FirestoreDocument("trip", mapOf("ownerId" to "owner")), source.getTrip("trip"))
    verify(exactly = 1) { reference.get(Source.SERVER) }
  }

  @Test
  fun confirmedAbsenceIsReturnedAsNull() = runTest {
    val source = source()
    every { snapshot.exists() } returns false
    assertNull(source.getTrip("trip"))
  }

  @Test
  fun pendingWriteIsRejectedBeforeDecodingOrReportingAbsence() = runTest {
    assertUnconfirmed(source(pending = true))
  }

  @Test
  fun cachedSnapshotIsRejectedBeforeDecodingOrReportingAbsence() = runTest {
    assertUnconfirmed(source(cached = true))
  }

  private suspend fun assertUnconfirmed(source: FirebaseTripFirestoreDataSource) {
    try {
      source.getTrip("trip")
      fail("Unconfirmed data must fail")
    } catch (error: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.UNAVAILABLE, error.code)
    }
    verify(exactly = 0) {
      snapshot.exists()
      snapshot.data
    }
  }
}
