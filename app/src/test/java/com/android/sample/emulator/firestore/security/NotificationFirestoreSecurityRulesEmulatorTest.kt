package com.android.sample.emulator.firestore.security

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.*
import com.android.sample.data.repository.NotificationFirestoreData.IS_READ
import com.android.sample.data.repository.NotificationFirestoreData.NEW_ORDERS
import com.android.sample.data.repository.NotificationFirestoreData.ORDER_DECISIONS
import com.android.sample.data.repository.NotificationFirestoreData.ORDER_ID
import com.android.sample.data.repository.NotificationFirestoreData.VISIBLE
import com.android.sample.emulator.auth.FirebaseAuthEmulator.runTest
import com.android.sample.model.*
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Source
import java.util.UUID
import kotlinx.coroutines.*
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
        assertPermissionDenied {
          root.collection(NotificationFirestoreData.INBOX).get(Source.SERVER).await()
        }
        assertPermissionDenied {
          root
              .collection(NotificationFirestoreData.INBOX)
              .document(event.id)
              .update(IS_READ, true)
              .await()
        }
        val preferences =
            root
                .collection(NotificationFirestoreData.PREFERENCES)
                .document(NotificationFirestoreData.SETTINGS)
        assertPermissionDenied { preferences.get(Source.SERVER).await() }
        assertPermissionDenied {
          preferences
              .set(NotificationFirestoreData.preferenceData(NotificationPreferences(false, false)))
              .await()
        }
        assertPermissionDenied {
          attacker.firestore
              .collection(NotificationFirestoreData.EVENTS)
              .document(event.id)
              .get(Source.SERVER)
              .await()
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
      val ref =
          client.firestore
              .notificationUser(event.recipientId)
              .collection(NotificationFirestoreData.INBOX)
              .document(event.id)
      for (mutation in
          listOf(
              mapOf(VISIBLE to false),
              mapOf(ORDER_ID to "other"),
              mapOf(IS_READ to "true"),
          )) {
        assertPermissionDenied { ref.update(mutation).await() }
      }
      repo(client).markAsRead(event.id)
      assertPermissionDenied { ref.update(IS_READ, false).await() }
      assertPermissionDenied { ref.delete().await() }
    }
  }

  @Test
  fun clientCannotForgeCanonicalEventsOrInboxEntries() = runTest {
    withClients("recipient" to true) { (client) ->
      val event = seed(client)
      val data = NotificationFirestoreData.eventData(event)
      val entries =
          client.firestore
              .notificationUser(event.recipientId)
              .collection(NotificationFirestoreData.INBOX)
      assertPermissionDenied {
        client.firestore
            .collection(NotificationFirestoreData.EVENTS)
            .document("forged")
            .set(data)
            .await()
      }
      assertPermissionDenied {
        entries.document("missing").set(data + mapOf(VISIBLE to true, IS_READ to false)).await()
      }
      for (invalid in
          listOf(mapOf(IS_READ to true), mapOf(ORDER_ID to "other"), mapOf(VISIBLE to false))) {
        assertPermissionDenied {
          entries
              .document(event.id)
              .set(data + mapOf(VISIBLE to true, IS_READ to false) + invalid)
              .await()
        }
      }
      assertEquals(listOf(OrderNotification(event)), inbox(client))
    }
  }

  @Test
  fun malformedPreferencesAreDeniedAndMissingPreferencesUseDefaults() = runTest {
    withClients("recipient" to true) { (client) ->
      val uid = client.auth.currentUser!!.uid
      val settings =
          client.firestore
              .notificationUser(uid)
              .collection(NotificationFirestoreData.PREFERENCES)
              .document(NotificationFirestoreData.SETTINGS)
      for (invalid in
          listOf(
              mapOf(NEW_ORDERS to true),
              mapOf(NEW_ORDERS to "true", ORDER_DECISIONS to false),
              mapOf(NEW_ORDERS to true, ORDER_DECISIONS to true, "extra" to true),
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
  fun concurrentProcessingAndEqualTimestampsRemainDeterministic() = runTest {
    withClients("recipient" to true) { (client) ->
      val events = List(2) { seed(client) }
      val expected = events.sortedBy { it.id }.map { OrderNotification(it) }
      val results = listOf(async { inbox(client) }, async { inbox(client) }).awaitAll()
      results.forEach { assertEquals(expected, it) }
      repo(client).markAsRead(expected.first().event.id)
      assertTrue(inbox(client).first().isRead)
    }
  }

  private fun seed(
      client: EmulatorClient,
      type: NotificationEventType = NotificationEventType.ORDER_PLACED,
  ): OrderNotificationEvent {
    val event =
        OrderNotificationEvent(
            UUID.randomUUID().toString(),
            client.auth.currentUser!!.uid,
            type,
            EVENT_TIME.toInstant(),
        )
    createDocumentAsOwner(
        NotificationFirestoreData.EVENTS,
        event.id,
        NotificationFirestoreData.eventData(event),
    )
    return event
  }

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
