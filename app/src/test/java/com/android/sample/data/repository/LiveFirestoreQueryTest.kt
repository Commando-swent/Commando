package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.google.firebase.auth.*
import com.google.firebase.firestore.*
import io.mockk.*
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** SDK callback contracts: unconfirmed data, quiet logout and cancellation cleanup. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveFirestoreQueryTest {
  private val db = mockk<FirebaseFirestore>()
  private val auth = mockk<FirebaseAuth>()
  private val query = mockk<Query>()
  private val registration = mockk<ListenerRegistration>(relaxed = true)
  private val snapshots = slot<EventListener<QuerySnapshot>>()
  private val sessions = slot<FirebaseAuth.AuthStateListener>()
  private var user: FirebaseUser? = mockk { every { uid } returns "viewer" }

  @Before
  fun setup() {
    mockkStatic(FirebaseAuth::class)
    every { db.app } returns mockk()
    every { FirebaseAuth.getInstance(any()) } returns auth
    every { auth.currentUser } answers { user }
    every { auth.addAuthStateListener(capture(sessions)) } just Runs
    every { auth.removeAuthStateListener(any()) } just Runs
    every {
      query.addSnapshotListener(any<Executor>(), MetadataChanges.INCLUDE, capture(snapshots))
    } returns registration
  }

  @After fun clear() = unmockkAll()

  @Test
  fun cacheAndPendingAreUnknownUntilServerConfirmationAndCancellationCleansUp() = runTest {
    val values = mutableListOf<List<FirestoreDocument>?>()
    val job = backgroundScope.launch {
      query.observeServerDocuments(db, "viewer").collect { values += it }
    }
    runCurrent()
    for ((cache, pending) in listOf(true to false, false to true, false to false)) {
      val metadata =
          mockk<SnapshotMetadata> {
            every { isFromCache } returns cache
            every { hasPendingWrites() } returns pending
          }
      val snapshot =
          mockk<QuerySnapshot> {
            every { this@mockk.metadata } returns metadata
            every { documents } returns emptyList()
          }
      snapshots.captured.onEvent(snapshot, null)
      runCurrent()
    }
    assertEquals(listOf(null, null, emptyList<FirestoreDocument>()), values)
    job.cancel()
    runCurrent()
    verify(exactly = 1) {
      registration.remove()
      auth.removeAuthStateListener(sessions.captured)
    }
  }

  @Test fun quietLogoutClosesTheQueryAndReleasesBothListeners() = assertLogout(false)

  @Test fun snapshotAfterLogoutCannotEmitPreviousSessionData() = assertLogout(true)

  private fun assertLogout(viaSnapshot: Boolean) = runTest {
    var failure: Throwable? = null
    val job = backgroundScope.launch {
      query.observeServerDocuments(db, "viewer").catch { failure = it }.collect()
    }
    runCurrent()
    user = null
    if (viaSnapshot) snapshots.captured.onEvent(null, null)
    else sessions.captured.onAuthStateChanged(auth)
    runCurrent()
    assertTrue(job.isCompleted)
    assertEquals(
        FirebaseFirestoreException.Code.UNAUTHENTICATED,
        (failure as FirebaseFirestoreException).code,
    )
    verify(exactly = 1) {
      registration.remove()
      auth.removeAuthStateListener(sessions.captured)
    }
  }
}
