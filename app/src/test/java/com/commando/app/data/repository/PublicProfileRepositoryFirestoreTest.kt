package com.commando.app.data.repository

// AI assistance: OpenAI Codex.
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PublicProfileRepositoryFirestoreTest {
  private val firestore = mockk<FirebaseFirestore>()
  private val transaction = mockk<Transaction>()
  private val profiles = mockk<CollectionReference>()
  private val ownerProfile = mockk<DocumentReference>()
  private val viewerProfile = mockk<DocumentReference>()
  private var uid: String? = "viewer"
  private val repository = PublicProfileRepositoryFirestore({ uid }, firestore)

  @Before
  fun setUp() {
    every { firestore.collection("publicProfiles") } returns profiles
    every { profiles.document("owner") } returns ownerProfile
    every { profiles.document("viewer") } returns viewerProfile
    every { firestore.runTransaction<DocumentSnapshot>(any()) } answers
        {
          Tasks.forResult(firstArg<Transaction.Function<DocumentSnapshot>>().apply(transaction))
        }
  }

  private fun publishedName(name: String): DocumentSnapshot = mockk {
    every { exists() } returns true
    every { getString("displayName") } returns name
  }

  private fun pendingRead() =
      TaskCompletionSource<DocumentSnapshot>().also { response ->
        every { firestore.runTransaction<DocumentSnapshot>(any()) } returns response.task
      }

  @Test
  fun readsTheSuppliedOwnersPublicNameInAReadOnlyTransaction() = runTest {
    every { transaction.get(ownerProfile) } returns publishedName("Alex")

    assertEquals("Alex", repository.getDisplayName("owner"))
    verify(exactly = 1) { transaction.get(ownerProfile) }
    confirmVerified(transaction)
  }

  @Test
  fun missingProfileReturnsNull() = runTest {
    val missing = mockk<DocumentSnapshot> { every { exists() } returns false }
    every { transaction.get(ownerProfile) } returns missing

    assertNull(repository.getDisplayName("owner"))
  }

  @Test
  fun readPropagatesTheFirestoreFailure() = runTest {
    val offline = FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)
    every { firestore.runTransaction<DocumentSnapshot>(any()) } returns Tasks.forException(offline)

    assertSame(offline, runCatching { repository.getDisplayName("owner") }.exceptionOrNull())
  }

  @Test
  fun publishingRejectsWhitespaceBeforeContactingFirestore() = runTest {
    for (name in listOf("", " \t", "\u00A0", "\u0085\u2003\u202F", "\u001C")) {
      val failure = runCatching { repository.publishDisplayName(name) }.exceptionOrNull()
      assertTrue(failure is IllegalArgumentException)
    }
    verify { firestore wasNot Called }
  }

  @Test
  fun publicNameLimitCountsSupplementaryCharactersOnce() {
    for (character in listOf("a", "😀")) {
      assertTrue(isValidPublicName(character.repeat(80)))
      assertFalse(isValidPublicName(character.repeat(81)))
    }
  }

  @Test
  fun publicNamesRejectLineBreaksAndUnpairedSurrogates() {
    for (name in listOf("A\nB", "A\rB", "A\uD800", "\uDC00B")) {
      assertFalse(isValidPublicName(name))
    }
  }

  @Test
  fun publishingAgainReplacesTheNameOnTheCurrentOwnersDocument() = runTest {
    val entered = "\u00A0Alex 😀 "
    val first = mapOf("displayName" to entered)
    val second = mapOf("displayName" to "Sam")
    every { viewerProfile.set(any()) } returns Tasks.forResult(null)

    repository.publishDisplayName(entered)
    repository.publishDisplayName("Sam")

    verifySequence {
      viewerProfile.set(first)
      viewerProfile.set(second)
    }
  }

  @Test
  fun removesOnlyTheCurrentOwnersPublicName() = runTest {
    every { viewerProfile.delete() } returns Tasks.forResult(null)

    repository.removeDisplayName()

    verify(exactly = 1) { viewerProfile.delete() }
    verify(exactly = 0) { ownerProfile.delete() }
  }

  @Test
  fun missingSessionRejectsAllOperationsBeforeFirestoreAccess() = runTest {
    uid = null

    assertTrue(
        runCatching { repository.getDisplayName("owner") }.exceptionOrNull()
            is IllegalStateException
    )
    assertTrue(
        runCatching { repository.publishDisplayName("Alex") }.exceptionOrNull()
            is IllegalStateException
    )
    assertTrue(
        runCatching { repository.removeDisplayName() }.exceptionOrNull() is IllegalStateException
    )
    verify { firestore wasNot Called }
  }

  @Test
  fun blankViewerUidCannotStartARead() = runTest {
    uid = " "

    val result = runCatching { repository.getDisplayName("owner") }

    assertTrue("A blank viewer UID returned a public name", result.isFailure)
    verify { firestore wasNot Called }
  }

  @Test
  fun invalidOwnerIdIsRejectedBeforeFirestoreAccess() = runTest {
    val error = runCatching { repository.getDisplayName("a/b") }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
    verify { firestore wasNot Called }
  }

  @Test
  fun accountChangeDiscardsAPendingSuccessfulRead() = runTest {
    val response = pendingRead()
    val read =
        async(start = CoroutineStart.UNDISPATCHED) {
          runCatching { repository.getDisplayName("owner") }
        }

    uid = "next-viewer"
    response.setResult(publishedName("Alex"))

    assertTrue(read.await().exceptionOrNull() is IllegalStateException)
  }

  @Test
  fun accountChangeDiscardsAPendingReadFailure() = runTest {
    val response = pendingRead()
    val read =
        async(start = CoroutineStart.UNDISPATCHED) {
          runCatching { repository.getDisplayName("owner") }
        }

    uid = "next-viewer"
    response.setException(
        FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)
    )

    assertTrue(read.await().exceptionOrNull() is IllegalStateException)
  }

  @Test
  fun cancellationDuringAReadSurvivesAnAccountChange() = runTest {
    pendingRead()
    var observed: Throwable? = null
    val read =
        launch(start = CoroutineStart.UNDISPATCHED) {
          observed = runCatching { repository.getDisplayName("owner") }.exceptionOrNull()
        }

    uid = "next-viewer"
    read.cancelAndJoin()

    assertTrue("Expected cancellation, got $observed", observed is CancellationException)
  }
}
