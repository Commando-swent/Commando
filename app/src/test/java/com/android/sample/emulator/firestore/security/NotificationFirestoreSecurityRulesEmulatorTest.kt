package com.android.sample.emulator.firestore.security

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.*
import com.android.sample.data.repository.NotificationFirestoreData as Schema
import com.android.sample.emulator.auth.FirebaseAuthEmulator.runTest
import com.android.sample.model.*
import com.android.sample.model.NotificationEventType.*
import com.google.firebase.Timestamp
import com.google.firebase.firestore.*
import io.mockk.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.tasks.await
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Recipient storage/rules with admin-seeded canonical events; publication is a separate increment.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationFirestoreSecurityRulesEmulatorTest {
  @Test
  fun readStateAndPreferencesSurviveAFreshAuthenticatedClient() = runTest {
    withClients("first" to false, "fresh" to false) { (first, fresh) ->
      val email = "notifications-${UUID.randomUUID()}@example.test"
      val password = "emulator-password"
      first.auth.createUserWithEmailAndPassword(email, password).await()
      val event = seed(first)
      assertEquals(listOf(OrderNotification(event)), inbox(first))
      assertEquals(OrderResult.Success(Unit), repo(first).markAsRead(event.id))
      assertEquals(OrderResult.Success(Unit), repo(first).markAsRead(event.id))
      val settings = NotificationPreferences(false, true)
      assertEquals(OrderResult.Success(Unit), repo(first).updatePreferences(settings))
      fresh.auth.signInWithEmailAndPassword(email, password).await()
      assertEquals(listOf(OrderNotification(event, true)), inbox(fresh))
      assertEquals(OrderResult.Success(settings), repo(fresh).getPreferences())
    }
  }

  @Test
  fun preferenceChangesDoNotReplayMutedEventsOrHideExistingEntries() = runTest {
    withClients("recipient" to true) { (client) ->
      val existing = seed(client)
      inbox(client)
      repo(client).updatePreferences(NotificationPreferences(false, false))
      val muted = NotificationEventType.entries.map { seed(client, it) }
      assertEquals(listOf(OrderNotification(existing)), inbox(client))
      repo(client).updatePreferences(NotificationPreferences())
      assertEquals(listOf(OrderNotification(existing)), inbox(client))
      assertEquals(
          OrderResult.Error(OrderError.NotFound),
          repo(client).markAsRead(muted.first().id),
      )
      val next = seed(client)
      assertEquals(setOf(existing, next), inbox(client).map { it.event }.toSet())
    }
  }

  @Test
  fun foreignAndSignedOutClientsCannotAccessRecipientData() = runTest {
    withClients("recipient" to true, "other" to true, "signedOut" to false) {
        (client, other, signedOut) ->
      val event = seed(client)
      inbox(client)
      val user = client.auth.currentUser!!.uid
      for (attacker in listOf(other, signedOut)) {
        val root = attacker.firestore.notificationUser(user)
        assertPermissionDenied { root.collection(Schema.INBOX).get(Source.SERVER).await() }
        assertPermissionDenied {
          root.collection(Schema.INBOX).document(event.id).update(Schema.IS_READ, true).await()
        }
        val preferences = root.collection(Schema.PREFERENCES).document(Schema.SETTINGS)
        assertPermissionDenied { preferences.get(Source.SERVER).await() }
        assertPermissionDenied {
          preferences.set(Schema.preferenceData(NotificationPreferences(false, false))).await()
        }
        assertPermissionDenied {
          attacker.firestore.collection(Schema.EVENTS).document(event.id).get(Source.SERVER).await()
        }
      }
      assertTrue(inbox(other).isEmpty())
    }
  }

  @Test
  fun notificationContentIsImmutableAndReadStateCannotBeReversed() = runTest {
    withClients("recipient" to true) { (client) ->
      val event = seed(client)
      inbox(client)
      val ref = entries(client).document(event.id)
      for (mutation in
          listOf(
              mapOf(Schema.VISIBLE to false),
              mapOf(Schema.ORDER_ID to "other"),
              mapOf(Schema.IS_READ to "true"),
          )) {
        assertPermissionDenied { ref.update(mutation).await() }
      }
      assertEquals(OrderResult.Success(Unit), repo(client).markAsRead(event.id))
      assertTrue(inbox(client).single().isRead)
      assertPermissionDenied { ref.update(Schema.IS_READ, false).await() }
      assertPermissionDenied { ref.delete().await() }
    }
  }

  @Test
  fun clientCannotForgeCanonicalEventsOrInboxEntries() = runTest {
    withClients("recipient" to true) { (client) ->
      val event = seed(client)
      val data = Schema.eventData(event)
      val contents = data + mapOf(Schema.VISIBLE to true, Schema.IS_READ to false)
      val entries = entries(client)
      assertPermissionDenied {
        client.firestore.collection(Schema.EVENTS).document("forged").set(data).await()
      }
      assertPermissionDenied { entries.document("missing").set(contents).await() }
      for (invalid in
          listOf(
              mapOf(Schema.IS_READ to true),
              mapOf(Schema.ORDER_ID to "other"),
              mapOf(Schema.VISIBLE to false),
          )) {
        assertPermissionDenied { entries.document(event.id).set(contents + invalid).await() }
      }
      assertEquals(listOf(OrderNotification(event)), inbox(client))
    }
  }

  @Test
  fun malformedPreferencesAreDeniedAndMissingPreferencesUseDefaults() = runTest {
    withClients("recipient" to true) { (client) ->
      val root = client.firestore.notificationUser(client.auth.currentUser!!.uid)
      val settings = root.collection(Schema.PREFERENCES).document(Schema.SETTINGS)
      for (invalid in
          listOf(
              mapOf(Schema.NEW_ORDERS to true),
              mapOf(Schema.NEW_ORDERS to "true", Schema.ORDER_DECISIONS to false),
              mapOf(Schema.NEW_ORDERS to true, Schema.ORDER_DECISIONS to true, "extra" to true),
          )) {
        assertPermissionDenied { settings.set(invalid).await() }
      }
      assertEquals(OrderResult.Success(NotificationPreferences()), repo(client).getPreferences())
      assertEquals(OrderResult.Error(OrderError.InvalidData), repo(client).markAsRead("a/b"))
      assertEquals(OrderResult.Error(OrderError.NotFound), repo(client).markAsRead("missing"))
    }
  }

  @Test
  fun repositoryBoundToAnEndedSessionRejectsEveryOperation() = runTest {
    withClients("recipient" to true) { (client) ->
      val uid = client.auth.currentUser!!.uid
      val stale = NotificationRepositoryFirestore({ uid }, client.firestore)
      client.auth.signOut()
      client.auth.signInAnonymously().await()
      val denied = OrderResult.Error(OrderError.NotAuthenticated)
      assertEquals(denied, stale.getNotifications())
      assertEquals(denied, stale.getPreferences())
      assertEquals(denied, stale.markAsRead("missing"))
      assertEquals(denied, stale.updatePreferences(NotificationPreferences(false, false)))
    }
  }

  @Test
  fun newestFirstOrderingUsesStableIdsForEqualTimestamps() = runTest {
    withClients("recipient" to true) { (client) ->
      val older = seed(client)
      val latest =
          List(2) { seed(client, time = Timestamp(EVENT_TIME.seconds + 1, EVENT_TIME.nanoseconds)) }
      val expected = (latest.sortedBy { it.id } + older).map { OrderNotification(it) }
      assertEquals(expected, inbox(client))
    }
  }

  @Test
  fun eachPreferenceFiltersOnlyItsOwnCategory() = runTest {
    for ((preferences, visible) in
        listOf(
            NotificationPreferences(false, true) to setOf(ORDER_ACCEPTED, ORDER_REJECTED),
            NotificationPreferences(true, false) to setOf(ORDER_PLACED),
        )) {
      withClients("recipient" to true) { (client) ->
        assertEquals(OrderResult.Success(Unit), repo(client).updatePreferences(preferences))
        NotificationEventType.entries.forEach { seed(client, it) }
        assertEquals(visible, inbox(client).map { it.event.type }.toSet())
      }
    }
  }

  @Test
  fun transactionRetryPreservesAnEntryMarkedReadByAnotherClient() = runTest {
    withClients("first" to false, "second" to false) { (first, second) ->
      val email = "retry-${UUID.randomUUID()}@example.test"
      first.auth.createUserWithEmailAndPassword(email, "emulator-password").await()
      second.auth.signInWithEmailAndPassword(email, "emulator-password").await()
      val event = seed(first)
      val staged = CompletableDeferred<Unit>()
      val resume = CountDownLatch(1)
      val attempts = AtomicInteger()
      val firestore = spyk(first.firestore)
      // Transaction references must remain bound to the original Firebase client.
      every { firestore.collection(any()) } answers { first.firestore.collection(firstArg()) }
      every { firestore.runTransaction(any<Transaction.Function<OrderNotification?>>()) } answers
          {
            val operation = firstArg<Transaction.Function<OrderNotification?>>()
            first.firestore.runTransaction { transaction ->
              val result = operation.apply(transaction)
              if (attempts.incrementAndGet() == 1) {
                assertEquals(OrderNotification(event), result)
                staged.complete(Unit)
                check(resume.await(TRANSACTION_GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
              }
              result
            }
          }
      val pending =
          async(Dispatchers.Default) {
            NotificationRepositoryFirestore({ first.auth.currentUser?.uid }, firestore)
                .getNotifications()
          }
      try {
        select<Unit> {
          staged.onAwait {}
          pending.onAwait { fail("Processing completed before the conflict: $it") }
        }
        assertEquals(listOf(OrderNotification(event)), inbox(second))
        assertEquals(OrderResult.Success(Unit), repo(second).markAsRead(event.id))
      } finally {
        resume.countDown()
      }
      val expected = listOf(OrderNotification(event, true))
      assertEquals(OrderResult.Success(expected), pending.await())
      assertTrue("Firestore must retry the conflicted transaction", attempts.get() >= 2)
      assertEquals(expected, inbox(first))
      assertEquals(1, entries(second).get(Source.SERVER).await().size())
    }
  }

  private fun seed(
      client: EmulatorClient,
      type: NotificationEventType = ORDER_PLACED,
      time: Timestamp = EVENT_TIME,
  ): OrderNotificationEvent {
    val uid = client.auth.currentUser!!.uid
    val event = OrderNotificationEvent(UUID.randomUUID().toString(), uid, type, time.toInstant())
    createDocumentAsOwner(Schema.EVENTS, event.id, Schema.eventData(event))
    return event
  }

  private fun entries(client: EmulatorClient) =
      client.firestore.notificationUser(client.auth.currentUser!!.uid).collection(Schema.INBOX)

  private fun repo(client: EmulatorClient) =
      NotificationRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)

  private suspend fun inbox(client: EmulatorClient): List<OrderNotification> {
    val result = repo(client).getNotifications()
    assertTrue("Inbox failed: $result", result is OrderResult.Success)
    return (result as OrderResult.Success).data
  }
}

// A shared sub-second fixture catches timestamp precision loss during persistence.
private val EVENT_TIME = Timestamp(100, 123_000)
private const val TRANSACTION_GATE_TIMEOUT_SECONDS = 10L
