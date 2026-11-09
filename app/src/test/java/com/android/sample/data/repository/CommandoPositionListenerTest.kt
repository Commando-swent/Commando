package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.CommandoPosition
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import io.mockk.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Exercise the SDK callbacks explicitly: lifecycle cleanup and server confirmation are contracts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandoPositionListenerTest {
  private val firestore = mockk<FirebaseFirestore>()
  private val auth = mockk<FirebaseAuth>()
  private var authenticated: FirebaseUser? = mockk { every { uid } returns "viewer" }
  private val reference = mockk<DocumentReference>()
  private val registration = mockk<ListenerRegistration>(relaxed = true)
  private val snapshots = slot<EventListener<DocumentSnapshot>>()
  private val sessions = slot<FirebaseAuth.AuthStateListener>()
  private val now = Instant.parse("2026-11-09T12:00:00Z")
  private val fix = CommandoPosition(46.52, 6.56, 30.0, now)
  private val repo =
      CommandoPositionRepositoryFirestore(
          { "viewer" },
          firestore,
          Clock.fixed(now, ZoneOffset.UTC),
      )

  @Before
  fun setUp() {
    mockkStatic(FirebaseAuth::class)
    every { firestore.app } returns mockk()
    every { FirebaseAuth.getInstance(any()) } returns auth
    every { auth.currentUser } answers { authenticated }
    val collection = mockk<CollectionReference>()
    every { firestore.collection("commandoPositions") } returns collection
    every { collection.document("owner") } returns reference
    every {
      reference.addSnapshotListener(
          any<Executor>(),
          MetadataChanges.INCLUDE,
          capture(snapshots),
      )
    } returns registration
    every { auth.addAuthStateListener(capture(sessions)) } just Runs
    every { auth.removeAuthStateListener(any()) } just Runs
  }

  @After fun tearDown() = unmockkAll()

  @Test
  fun cancellationRemovesBothSdkListeners() = runTest {
    val observation = backgroundScope.launch { repo.observe("owner").collect() }
    runCurrent()
    sessions.captured.onAuthStateChanged(auth)
    assertTrue(observation.isActive)
    observation.cancelAndJoin()
    verifyCleanup()
  }

  @Test
  fun logoutClosesAQuietStreamAndRemovesBothSdkListeners() = runTest {
    val failure = CompletableDeferred<Throwable>()
    val observation = backgroundScope.launch {
      try {
        repo.observe("owner").collect()
      } catch (error: SecurityException) {
        failure.complete(error)
      }
    }
    runCurrent()
    authenticated = null
    sessions.captured.onAuthStateChanged(auth)
    runCurrent()
    assertTrue("Auth callback must end a quiet stream", failure.isCompleted)
    assertTrue(observation.isCompleted)
    verifyCleanup()
  }

  @Test
  fun cacheAndPendingSnapshotsStayUnavailableUntilServerConfirmation() = runTest {
    val seen = mutableListOf<CommandoPosition?>()
    val failure = CompletableDeferred<Throwable>()
    backgroundScope.launch {
      try {
        repo.observe("owner").collect { seen += it }
      } catch (error: FirebaseFirestoreException) {
        failure.complete(error)
      }
    }
    runCurrent()
    for (document in listOf(null, snapshot(cache = true), snapshot(pending = true))) {
      snapshots.captured.onEvent(document, null)
      runCurrent()
      assertNull(seen.last())
    }
    snapshots.captured.onEvent(snapshot(), null)
    runCurrent()
    assertEquals(fix, seen.last())
    val denied =
        FirebaseFirestoreException(
            "Access revoked",
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
        )
    snapshots.captured.onEvent(null, denied)
    runCurrent()
    assertSame(denied, failure.await())
    verifyCleanup()
  }

  private fun verifyCleanup() {
    verify(exactly = 1) { registration.remove() }
    verify(exactly = 1) { auth.removeAuthStateListener(sessions.captured) }
  }

  @Test
  fun terminalOrderClosesTheStreamAndRemovesAllThreeListeners() = runTest {
    val viewers = mockk<CollectionReference>()
    val pointer = mockk<DocumentReference>()
    every { reference.collection("viewers") } returns viewers
    every { viewers.document("viewer") } returns pointer
    every { pointer.set(any<Map<String, Any>>()) } returns Tasks.forResult(null)
    val orders = mockk<CollectionReference>()
    val order = mockk<DocumentReference>()
    val changes = slot<EventListener<DocumentSnapshot>>()
    val orderRegistration = mockk<ListenerRegistration>(relaxed = true)
    every { firestore.collection("orders") } returns orders
    every { orders.document("order") } returns order
    every {
      order.addSnapshotListener(any<Executor>(), MetadataChanges.INCLUDE, capture(changes))
    } returns orderRegistration
    val failure = CompletableDeferred<Throwable>()
    backgroundScope.launch {
      try {
        repo.observe("owner", "order").collect()
      } catch (error: FirebaseFirestoreException) {
        failure.complete(error)
      }
    }
    runCurrent()
    val document = snapshot()
    every { document.get("requesterId") } returns "viewer"
    every { document.get("status") } returns "ACCEPTED"
    changes.captured.onEvent(document, null)
    runCurrent()
    assertFalse(failure.isCompleted)
    every { document.get("status") } returns "DELIVERED"
    changes.captured.onEvent(document, null)
    runCurrent()
    assertTrue(failure.isCompleted)
    verifyCleanup()
    verify(exactly = 1) { orderRegistration.remove() }
  }

  private fun snapshot(cache: Boolean = false, pending: Boolean = false): DocumentSnapshot {
    val metadata = mockk<SnapshotMetadata>()
    every { metadata.isFromCache } returns cache
    every { metadata.hasPendingWrites() } returns pending
    return mockk {
      every { this@mockk.metadata } returns metadata
      every { data } returns
          mutableMapOf(
              "latitude" to fix.latitude,
              "longitude" to fix.longitude,
              "accuracyMeters" to fix.accuracyMeters,
              "capturedAt" to Timestamp(now),
          )
    }
  }
}
