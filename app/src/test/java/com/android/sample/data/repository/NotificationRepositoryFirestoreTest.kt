package com.android.sample.data.repository

// AI assistance: OpenAI Codex (review regression tests).
import com.android.sample.data.repository.NotificationFirestoreData as Schema
import com.android.sample.model.*
import com.android.sample.model.NotificationEventType.ORDER_PLACED
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import io.mockk.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Rejects malformed notification data and stale reads after an account change. */
@RunWith(RobolectricTestRunner::class)
class NotificationRepositoryFirestoreTest {
  private val firestore = mockk<FirebaseFirestore>(relaxed = true)
  private val app = mockk<FirebaseApp>()
  private val auth = mockk<FirebaseAuth>()
  private val user = mockk<FirebaseUser>()
  private val settings = mockk<DocumentReference>()
  private val events = mockk<QuerySnapshot>()
  private val inbox = mockk<QuerySnapshot>()
  private var uid: String? = "recipient"
  private val repository = NotificationRepositoryFirestore({ uid }, firestore)
  private val event = OrderNotificationEvent("order", "recipient", ORDER_PLACED, Instant.EPOCH)

  @Before
  fun setUp() {
    mockkStatic(FirebaseAuth::class)
    every { firestore.app } returns app
    every { FirebaseAuth.getInstance(app) } returns auth
    every { auth.currentUser } returns user
    every { user.uid } returns "recipient"
    val account = firestore.notificationUser("recipient")
    every { account.collection(Schema.PREFERENCES).document(Schema.SETTINGS) } returns settings
    every { settings.get(Source.SERVER) } returns Tasks.forResult(snapshot(preferenceData()))
    every {
      firestore
          .collection(Schema.EVENTS)
          .whereEqualTo(Schema.RECIPIENT_ID, "recipient")
          .get(Source.SERVER)
    } returns Tasks.forResult(events)
    every { account.collection(Schema.INBOX).get(Source.SERVER) } returns Tasks.forResult(inbox)
    every { events.documents } returns listOf(snapshot(Schema.eventData(event)))
    every { inbox.documents } returns emptyList()
  }

  @After fun tearDown() = unmockkStatic(FirebaseAuth::class)

  @Test
  fun unavailableProviderReturnsAnError() = runTest {
    every { settings.get(Source.SERVER) } returns
        Tasks.forException(
            FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)
        )
    assertEquals(OrderResult.Error(OrderError.Unknown), repository.getPreferences())
  }

  @Test
  fun accountChangeDiscardsAPendingSuccessfulRead() = runTest {
    val response = TaskCompletionSource<DocumentSnapshot>()
    every { settings.get(Source.SERVER) } returns response.task
    val pending = async(start = CoroutineStart.UNDISPATCHED) { repository.getPreferences() }
    uid = "next-account"
    response.setResult(snapshot(preferenceData()))
    assertEquals(OrderResult.Error(OrderError.NotAuthenticated), pending.await())
  }

  @Test
  fun malformedSettingsReturnInvalidData() = runTest {
    for (data in
        listOf(
            emptyMap(),
            preferenceData() + (Schema.NEW_ORDERS to null),
            preferenceData() + (Schema.NEW_ORDERS to "true"),
            preferenceData() + (Schema.ORDER_DECISIONS to null),
            preferenceData() + (Schema.ORDER_DECISIONS to "true"),
        )) {
      every { settings.get(Source.SERVER) } returns Tasks.forResult(snapshot(data))
      assertEquals(OrderResult.Error(OrderError.InvalidData), repository.getPreferences())
    }
  }

  @Test
  fun malformedEventsFailBeforeInboxMaterialization() = runTest {
    val data = Schema.eventData(event)
    for (document in
        listOf(
            snapshot(null),
            snapshot(data + (Schema.ORDER_ID to null)),
            snapshot(data + (Schema.ORDER_ID to 1)),
            snapshot(data + (Schema.ORDER_ID to "a/b")),
            snapshot(data + (Schema.RECIPIENT_ID to null)),
            snapshot(data + (Schema.RECIPIENT_ID to 1)),
            snapshot(data + (Schema.RECIPIENT_ID to "a/b")),
            snapshot(data + (Schema.TYPE to "unknown")),
            snapshot(data + (Schema.OCCURRED_AT to "now")),
            snapshot(data, "noncanonical"),
        )) {
      every { events.documents } returns listOf(document)
      assertEquals(OrderResult.Error(OrderError.InvalidData), repository.getNotifications())
    }
    verify(exactly = 0) { firestore.runTransaction(any<Transaction.Function<Any?>>()) }
  }

  @Test
  fun inconsistentOrMalformedInboxEntriesAreRejected() = runTest {
    val data = Schema.eventData(event) + mapOf(Schema.IS_READ to false, Schema.VISIBLE to true)
    for (invalid in
        listOf(
            null,
            data - Schema.IS_READ,
            data + (Schema.IS_READ to null),
            data + (Schema.IS_READ to "false"),
            data + (Schema.VISIBLE to null),
            data + (Schema.VISIBLE to "true"),
            data + (Schema.TYPE to "unknown"),
            data + (Schema.OCCURRED_AT to com.google.firebase.Timestamp(1, 0)),
        )) {
      every { inbox.documents } returns listOf(snapshot(invalid))
      assertEquals(OrderResult.Error(OrderError.InvalidData), repository.getNotifications())
    }
    every { inbox.documents } returns listOf(snapshot(data + (Schema.RECIPIENT_ID to "another")))
    assertEquals(OrderResult.Error(OrderError.PermissionDenied), repository.getNotifications())
  }

  private fun preferenceData(): Map<String, Any?> = Schema.preferenceData(NotificationPreferences())

  private fun snapshot(data: Map<String, Any?>?, id: String = event.id): DocumentSnapshot =
      mockk<DocumentSnapshot> {
        every { this@mockk.id } returns id
        every { this@mockk.data } returns data
      }
}
