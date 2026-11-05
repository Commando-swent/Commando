package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirestoreDocumentTest {
  @Test
  fun snapshotKeepsTheStorageIdAndRawValuesForDomainValidation() {
    val fields = mapOf("id" to "migros:milk", "createdAt" to Timestamp(123, 0), "bad" to false)
    val snapshot = mockk<DocumentSnapshot>()
    every { snapshot.id } returns "storage-id"
    every { snapshot.data } returns fields

    assertEquals(FirestoreDocument("storage-id", fields), snapshot.toFirestoreDocument())
  }

  @Test
  fun absentSnapshotDataDoesNotInventRequiredDomainFields() {
    val snapshot = mockk<DocumentSnapshot>()
    every { snapshot.id } returns "missing-or-empty"
    every { snapshot.data } returns null

    assertEquals(FirestoreDocument("missing-or-empty", emptyMap()), snapshot.toFirestoreDocument())
  }
}
