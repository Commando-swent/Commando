package com.android.sample.data.repository

// AI assistance: OpenAI Codex. Contract choices remain documented for teammate review.
import com.android.sample.data.repository.NotificationFirestoreData.IS_READ
import com.android.sample.data.repository.NotificationFirestoreData.RECIPIENT_ID
import com.android.sample.data.repository.NotificationFirestoreData.VISIBLE
import com.android.sample.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
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
                .collection(NotificationFirestoreData.EVENTS)
                .whereEqualTo(RECIPIENT_ID, uid)
                .get(Source.SERVER)
                .await()
                .documents
        guard()
        val stored =
            firestore
                .notificationUser(uid)
                .collection(NotificationFirestoreData.INBOX)
                .get(Source.SERVER)
                .await()
                .documents
                .associateBy { it.id }
        guard()
        val notifications = documents.mapNotNull { document ->
          val event =
              NotificationFirestoreData.decodeEvent(document.id, document.data.orEmpty())
                  ?: throw error(FirebaseFirestoreException.Code.DATA_LOSS)
          if (event.recipientId != uid)
              throw error(FirebaseFirestoreException.Code.PERMISSION_DENIED)
          val existing = stored[event.id]
          if (existing == null) process(event, uid, guard)
          else {
            val entry = decodeInbox(existing, uid)
            if (entry.notification.event != event)
                throw error(FirebaseFirestoreException.Code.DATA_LOSS)
            entry.notification.takeIf { entry.visible }
          }
        }
        notifications.sortedWith(
            compareByDescending<OrderNotification> { it.event.occurredAt }.thenBy { it.event.id }
        )
      }

  override suspend fun markAsRead(notificationId: String): OrderResult<Unit> =
      session { uid, guard ->
        if (!notificationId.isDocumentId())
            throw error(FirebaseFirestoreException.Code.INVALID_ARGUMENT)
        val ref =
            firestore
                .notificationUser(uid)
                .collection(NotificationFirestoreData.INBOX)
                .document(notificationId)
        firestore
            .runTransaction { transaction ->
              guard()
              val snapshot = transaction.get(ref)
              guard()
              if (!snapshot.exists()) throw error(FirebaseFirestoreException.Code.NOT_FOUND)
              val entry = decodeInbox(snapshot, uid)
              if (!entry.visible) throw error(FirebaseFirestoreException.Code.NOT_FOUND)
              if (!entry.notification.isRead) transaction.update(ref, IS_READ, true)
              Unit
            }
            .await()
      }

  override suspend fun getPreferences(): OrderResult<NotificationPreferences> =
      session { uid, guard ->
        val data = preferenceRef(uid).get(Source.SERVER).await().data
        guard()
        NotificationFirestoreData.decodePreferences(data)
            ?: throw error(FirebaseFirestoreException.Code.DATA_LOSS)
      }

  override suspend fun updatePreferences(preferences: NotificationPreferences): OrderResult<Unit> =
      session { uid, guard ->
        guard()
        preferenceRef(uid).set(NotificationFirestoreData.preferenceData(preferences)).await()
      }

  private suspend fun process(
      event: OrderNotificationEvent,
      uid: String,
      guard: () -> Unit,
  ): OrderNotification? {
    val ref =
        firestore
            .notificationUser(uid)
            .collection(NotificationFirestoreData.INBOX)
            .document(event.id)
    return firestore
        .runTransaction { transaction ->
          guard()
          val existing = transaction.get(ref)
          val result =
              if (existing.exists()) {
                decodeInbox(existing, uid).also {
                  if (it.notification.event != event)
                      throw error(FirebaseFirestoreException.Code.DATA_LOSS)
                }
              } else {
                val preferences =
                    NotificationFirestoreData.decodePreferences(
                        transaction.get(preferenceRef(uid)).data
                    ) ?: throw error(FirebaseFirestoreException.Code.DATA_LOSS)
                guard()
                val visible = preferences.allows(event.type)
                transaction.set(
                    ref,
                    NotificationFirestoreData.eventData(event) +
                        mapOf(VISIBLE to visible, IS_READ to false),
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
    if (data.keys != NotificationFirestoreData.INBOX_FIELDS)
        throw error(FirebaseFirestoreException.Code.DATA_LOSS)
    val event =
        NotificationFirestoreData.decodeEvent(
            snapshot.id,
            data.filterKeys { it in NotificationFirestoreData.EVENT_FIELDS },
        ) ?: throw error(FirebaseFirestoreException.Code.DATA_LOSS)
    val read = data[IS_READ] as? Boolean ?: throw error(FirebaseFirestoreException.Code.DATA_LOSS)
    val visible =
        data[VISIBLE] as? Boolean ?: throw error(FirebaseFirestoreException.Code.DATA_LOSS)
    if (event.recipientId != uid) throw error(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    return InboxEntry(OrderNotification(event, read), visible)
  }

  /** Stored visibility is independent of the notification's persistent read state. */
  private data class InboxEntry(val notification: OrderNotification, val visible: Boolean)

  private fun preferenceRef(uid: String) =
      firestore
          .notificationUser(uid)
          .collection(NotificationFirestoreData.PREFERENCES)
          .document(NotificationFirestoreData.SETTINGS)

  private suspend fun <T> session(action: suspend (String, () -> Unit) -> T): OrderResult<T> {
    val context = currentCoroutineContext()
    context.ensureActive()
    val uid =
        currentUserId()?.takeIf { it.isDocumentId() }
            ?: return OrderResult.Error(OrderError.NotAuthenticated)
    val guard = {
      context.ensureActive()
      if (!sameSession(uid)) {
        throw error(FirebaseFirestoreException.Code.UNAUTHENTICATED)
      }
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

  private fun error(code: FirebaseFirestoreException.Code) =
      FirebaseFirestoreException("Notification operation failed", code)
}
