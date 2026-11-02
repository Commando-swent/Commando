package com.android.sample.data.repository

// AI assistance: OpenAI Codex. Contract choices remain documented for teammate review.
import com.android.sample.data.repository.NotificationFirestoreData as Schema
import com.android.sample.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await

/**
 * Server-backed recipient inbox. Persisted events are processed with the recipient's private
 * preferences in a transaction. An invisible record permanently deduplicates a muted event.
 */
class NotificationRepositoryFirestore(
    private val currentUserId: () -> String?,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : NotificationRepository {
  override suspend fun getNotifications(): OrderResult<List<OrderNotification>> =
      session { uid, guard ->
        val documents =
            firestore
                .collection(Schema.EVENTS)
                .whereEqualTo(Schema.RECIPIENT_ID, uid)
                .get(Source.SERVER)
                .await()
                .documents
        guard()
        val stored =
            firestore
                .notificationUser(uid)
                .collection(Schema.INBOX)
                .get(Source.SERVER)
                .await()
                .documents
                .associateBy { it.id }
        guard()
        val notifications = documents.mapNotNull { document ->
          val event =
              Schema.decodeEvent(document.id, document.data.orEmpty())
                  ?: throw error(Code.DATA_LOSS)
          if (event.recipientId != uid) throw error(Code.PERMISSION_DENIED)
          val existing = stored[event.id]
          if (existing == null) process(event, uid, guard)
          else {
            val entry = decodeInbox(existing, uid)
            if (entry.notification.event != event) throw error(Code.DATA_LOSS)
            entry.notification.takeIf { entry.visible }
          }
        }
        notifications.sortedWith(
            compareByDescending<OrderNotification> { it.event.occurredAt }.thenBy { it.event.id }
        )
      }

  override suspend fun markAsRead(notificationId: String): OrderResult<Unit> =
      session { uid, guard ->
        if (!notificationId.isDocumentId()) throw error(Code.INVALID_ARGUMENT)
        val ref = firestore.notificationUser(uid).collection(Schema.INBOX).document(notificationId)
        firestore
            .runTransaction { transaction ->
              guard()
              val snapshot = transaction.get(ref)
              guard()
              if (!snapshot.exists()) throw error(Code.NOT_FOUND)
              val entry = decodeInbox(snapshot, uid)
              if (!entry.visible) throw error(Code.NOT_FOUND)
              if (!entry.notification.isRead) transaction.update(ref, Schema.IS_READ, true)
              Unit
            }
            .await()
      }

  override suspend fun getPreferences(): OrderResult<NotificationPreferences> =
      session { uid, guard ->
        val data = preferenceRef(uid).get(Source.SERVER).await().data
        guard()
        Schema.decodePreferences(data) ?: throw error(Code.DATA_LOSS)
      }

  override suspend fun updatePreferences(preferences: NotificationPreferences): OrderResult<Unit> =
      session { uid, guard ->
        guard()
        preferenceRef(uid).set(Schema.preferenceData(preferences)).await()
      }

  private suspend fun process(
      event: OrderNotificationEvent,
      uid: String,
      guard: () -> Unit,
  ): OrderNotification? {
    val ref = firestore.notificationUser(uid).collection(Schema.INBOX).document(event.id)
    return firestore
        .runTransaction { transaction ->
          guard()
          val existing = transaction.get(ref)
          val result =
              if (existing.exists()) {
                decodeInbox(existing, uid).also {
                  if (it.notification.event != event) throw error(Code.DATA_LOSS)
                }
              } else {
                val preferences =
                    Schema.decodePreferences(transaction.get(preferenceRef(uid)).data)
                        ?: throw error(Code.DATA_LOSS)
                guard()
                val visible = preferences.allows(event.type)
                transaction.set(
                    ref,
                    Schema.eventData(event) +
                        mapOf(Schema.VISIBLE to visible, Schema.IS_READ to false),
                )
                InboxEntry(OrderNotification(event), visible)
              }
          guard()
          result.notification.takeIf { result.visible }
        }
        .await()
  }

  private fun decodeInbox(
      snapshot: DocumentSnapshot,
      uid: String,
  ): InboxEntry {
    val data = snapshot.data.orEmpty()
    if (data.keys != Schema.INBOX_FIELDS) throw error(Code.DATA_LOSS)
    val event =
        Schema.decodeEvent(
            snapshot.id,
            data.filterKeys { it in Schema.EVENT_FIELDS },
        ) ?: throw error(Code.DATA_LOSS)
    val read = data[Schema.IS_READ] as? Boolean ?: throw error(Code.DATA_LOSS)
    val visible = data[Schema.VISIBLE] as? Boolean ?: throw error(Code.DATA_LOSS)
    if (event.recipientId != uid) throw error(Code.PERMISSION_DENIED)
    return InboxEntry(OrderNotification(event, read), visible)
  }

  /** Stored visibility is independent of the notification's persistent read state. */
  private data class InboxEntry(val notification: OrderNotification, val visible: Boolean)

  private fun preferenceRef(uid: String) =
      firestore.notificationUser(uid).collection(Schema.PREFERENCES).document(Schema.SETTINGS)

  private suspend fun <T> session(action: suspend (String, () -> Unit) -> T): OrderResult<T> {
    val context = currentCoroutineContext()
    context.ensureActive()
    val uid =
        currentUserId()?.takeIf { it.isDocumentId() }
            ?: return OrderResult.Error(OrderError.NotAuthenticated)
    val guard = {
      context.ensureActive()
      if (!sameSession(uid)) throw error(Code.UNAUTHENTICATED)
    }
    return try {
      guard()
      val value = action(uid, guard)
      guard()
      OrderResult.Success(value)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (exception: Exception) {
      context.ensureActive()
      if (!sameSession(uid)) OrderResult.Error(OrderError.NotAuthenticated)
      else OrderResult.Error(exception.toOrderError())
    }
  }

  private fun sameSession(uid: String) =
      currentUserId() == uid && FirebaseAuth.getInstance(firestore.app).currentUser?.uid == uid

  private fun error(code: Code) = FirebaseFirestoreException("Notification operation failed", code)
}
