package com.commando.app.data.repository

// AI assistance: OpenAI Codex.
import com.commando.app.model.*
import com.commando.app.model.CHAT_WINDOW_SIZE
import com.commando.app.model.ChatMessage
import com.commando.app.model.OrderChat
import com.commando.app.model.OrderStatus
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import java.time.temporal.ChronoUnit.MICROS
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await

/** Server-confirmed immutable chat; atomic sends and participant-only Firestore rules. */
class OrderChatRepositoryFirestore(
    private val currentUserId: () -> String?,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : OrderChatRepository {
  private val auth
    get() = FirebaseAuth.getInstance(firestore.app)

  private fun order(id: String) = firestore.collection("orders").document(id)

  override suspend fun sendMessage(
      orderId: String,
      messageId: String,
      text: String,
  ): OrderResult<Unit> = send(orderId, messageId, text, null)

  override suspend fun sendDelayNotice(orderId: String, messageId: String, minutes: Int) =
      send(orderId, messageId, CHAT_DELAY_FALLBACK, minutes)

  override suspend fun proposeMeetup(orderId: String, messageId: String, proposal: MeetupProposal) =
      send(orderId, messageId, CHAT_MEETUP_FALLBACK, null, proposal)

  override suspend fun respondToMeetup(
      orderId: String,
      messageId: String,
      response: MeetupResponse,
  ): OrderResult<Unit> {
    val uid =
        currentUserId()?.takeIf { it.isDocumentId() }
            ?: return OrderResult.Error(OrderError.NotAuthenticated)
    return runFirestoreOperation(
        onFailure = {
          OrderResult.Error(
              if (sameSession(uid)) it.toOrderError() else OrderError.NotAuthenticated
          )
        }
    ) {
      if (
          !orderId.isDocumentId() || !messageId.isDocumentId() || response == MeetupResponse.PENDING
      )
          throw failure(Code.INVALID_ARGUMENT)
      val context = currentCoroutineContext()
      firestore
          .runTransaction { tx ->
            context.ensureActive()
            guard(uid)
            writeReply(tx, orderId, messageId, uid, response)
            Unit
          }
          .await()
      context.ensureActive()
      guard(uid)
      OrderResult.Success(Unit)
    }
  }

  private fun writeReply(
      tx: Transaction,
      orderId: String,
      messageId: String,
      uid: String,
      response: MeetupResponse,
  ) {
    val order = tx.get(order(orderId))
    if (order.getString("requesterId") != uid) throw failure(Code.PERMISSION_DENIED)
    val ref = order(orderId).collection("messages").document(messageId)
    val stored = tx.get(ref)
    if (stored.getString("senderId") == uid) throw failure(Code.PERMISSION_DENIED)
    val proposal = decodeMessage(stored).meetup ?: throw failure(Code.INVALID_ARGUMENT)
    if (proposal.response != response) {
      if (proposal.response != MeetupResponse.PENDING) throw failure(Code.INVALID_ARGUMENT)
      val trip =
          tx.get(
              firestore
                  .collection("trips")
                  .document(order.getString("tripId") ?: throw failure(Code.DATA_LOSS))
          )
      if (
          !OrderStatus.valueOf(order.getString("status").orEmpty()).canSendChat ||
              !TripStatus.valueOf(trip.getString("status").orEmpty()).canReportDelay ||
              !proposal.at.isAfter(java.time.Instant.now())
      )
          throw failure(Code.PERMISSION_DENIED)
      guard(uid)
      tx.update(
          ref,
          mapOf(
              "meetup.response" to response.name,
              "meetup.respondedAt" to FieldValue.serverTimestamp(),
          ),
      )
    }
  }

  private suspend fun send(
      orderId: String,
      messageId: String,
      text: String,
      delay: Int?,
      proposal: MeetupProposal? = null,
  ): OrderResult<Unit> {
    val meetup = proposal?.let { it.copy(at = it.at.truncatedTo(MICROS)) }
    val context = currentCoroutineContext()
    context.ensureActive()
    val uid =
        currentUserId()?.takeIf { it.isDocumentId() }
            ?: return OrderResult.Error(OrderError.NotAuthenticated)
    return runFirestoreOperation(
        onFailure = { error ->
          context.ensureActive()
          OrderResult.Error(
              if (sameSession(uid)) error.toOrderError() else OrderError.NotAuthenticated
          )
        }
    ) {
      if (
          !orderId.isDocumentId() ||
              !messageId.isDocumentId() ||
              !isValidChatPayload(text, delay, meetup)
      )
          throw failure(Code.INVALID_ARGUMENT)
      val orderRef = order(orderId)
      val messageRef = orderRef.collection("messages").document(messageId)
      firestore
          .runTransaction { transaction ->
            context.ensureActive()
            guard(uid)
            writeOnce(transaction, orderRef, messageRef, uid, text, delay, meetup)
            Unit
          }
          .await()
      context.ensureActive()
      guard(uid)
      OrderResult.Success(Unit)
    }
  }

  /** Reads the latest status before creating; committed retries never write again. */
  private fun writeOnce(
      transaction: Transaction,
      orderRef: DocumentReference,
      messageRef: DocumentReference,
      uid: String,
      text: String,
      delay: Int?,
      meetup: MeetupProposal?,
  ) {
    val storedOrder = transaction.get(orderRef)
    if (!storedOrder.exists()) throw failure(Code.NOT_FOUND)
    val previous = transaction.get(messageRef)
    if (previous.exists()) {
      if (
          previous.getString("senderId") != uid ||
              previous.getString("text") != text ||
              previous.getLong("delayMinutes") != delay?.toLong() ||
              decodeMessage(previous).meetup?.let {
                it.copy(response = MeetupResponse.PENDING, respondedAt = null)
              } != meetup
      )
          throw failure(Code.INVALID_ARGUMENT)
    } else {
      val status = OrderStatus.valueOf(storedOrder.getString("status").orEmpty())
      if (!status.canSendChat) throw failure(Code.PERMISSION_DENIED)
      if (meetup != null && !meetup.isFuturePending(java.time.Instant.now()))
          throw failure(Code.INVALID_ARGUMENT)
      if (delay != null || meetup != null) {
        val tripId = storedOrder.getString("tripId") ?: throw failure(Code.DATA_LOSS)
        val trip = transaction.get(firestore.collection("trips").document(tripId))
        if (
            trip.getString("ownerId") != uid ||
                !TripStatus.valueOf(trip.getString("status").orEmpty()).canReportDelay
        )
            throw failure(Code.PERMISSION_DENIED)
      }
      guard(uid)
      transaction.set(
          messageRef,
          mapOf(
              "senderId" to uid,
              "text" to text,
              "createdAt" to FieldValue.serverTimestamp(),
          ) +
              (delay?.let { mapOf("delayMinutes" to it) } ?: emptyMap()) +
              (meetup?.let {
                mapOf(
                    "meetup" to
                        mapOf(
                            "location" to it.location,
                            "at" to com.google.firebase.Timestamp(it.at.epochSecond, it.at.nano),
                            "response" to it.response.name,
                        )
                )
              } ?: emptyMap()),
      )
    }
  }

  override fun observeChat(orderId: String): Flow<OrderResult<OrderChat>> =
      flow<OrderResult<OrderChat>> {
            val uid =
                currentUserId()?.takeIf { it.isDocumentId() } ?: throw failure(Code.UNAUTHENTICATED)
            if (!orderId.isDocumentId()) throw failure(Code.INVALID_ARGUMENT)
            guard(uid)
            val orderRef = order(orderId)
            val orderSnapshots =
                watch<DocumentSnapshot>(uid) { callback ->
                      orderRef.addSnapshotListener({ it.run() }, MetadataChanges.INCLUDE, callback)
                    }
                    .filter { !it.metadata.isFromCache && !it.metadata.hasPendingWrites() }
            val messages =
                watch<QuerySnapshot>(uid) { callback ->
                      orderRef
                          .collection("messages")
                          .orderBy("createdAt", Query.Direction.DESCENDING)
                          .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
                          .limit(CHAT_WINDOW_SIZE)
                          .addSnapshotListener({ it.run() }, MetadataChanges.INCLUDE, callback)
                    }
                    .filter { !it.metadata.isFromCache && !it.metadata.hasPendingWrites() }
            emitAll(
                orderSnapshots.combine(messages) { snapshot, history ->
                  guard(uid)
                  if (!snapshot.exists()) throw failure(Code.NOT_FOUND)
                  val status = OrderStatus.valueOf(snapshot.getString("status").orEmpty())
                  if (status == OrderStatus.PENDING) throw failure(Code.PERMISSION_DENIED)
                  OrderResult.Success(
                      OrderChat(
                          status,
                          history.documents.reversed().map(::decodeMessage),
                      )
                  )
                }
            )
          }
          .catch { exception ->
            if (exception !is Exception) throw exception
            emit(OrderResult.Error(exception.toOrderError()))
          }

  /** Reject malformed structured records instead of silently rendering them as plain text. */
  private fun decodeMessage(document: DocumentSnapshot): ChatMessage {
    val text = document.getString("text") ?: throw failure(Code.DATA_LOSS)
    val rawDelay = document.get("delayMinutes")
    val delay =
        if (rawDelay == null) null
        else {
          if (rawDelay !is Long || rawDelay !in 1L..MAX_CHAT_DELAY_MINUTES.toLong())
              throw failure(Code.DATA_LOSS)
          rawDelay.toInt()
        }
    val meetup =
        document.get("meetup")?.let {
          val fields = it as? Map<*, *> ?: throw failure(Code.DATA_LOSS)
          if (!fields.keys.all { key -> key in setOf("location", "at", "response", "respondedAt") })
              throw failure(Code.DATA_LOSS)
          MeetupProposal(
              fields["location"] as? String ?: throw failure(Code.DATA_LOSS),
              (fields["at"] as? com.google.firebase.Timestamp)?.toInstant()
                  ?: throw failure(Code.DATA_LOSS),
              MeetupResponse.valueOf(
                  fields["response"] as? String ?: throw failure(Code.DATA_LOSS)
              ),
              fields["respondedAt"]?.let { time ->
                (time as? com.google.firebase.Timestamp)?.toInstant()
                    ?: throw failure(Code.DATA_LOSS)
              },
          )
        }
    if (!isValidChatPayload(text, delay, meetup)) throw failure(Code.DATA_LOSS)
    return ChatMessage(
        document.id,
        document.getString("senderId") ?: throw failure(Code.DATA_LOSS),
        text,
        document.getTimestamp("createdAt")?.toInstant() ?: throw failure(Code.DATA_LOSS),
        delay,
        meetup,
    )
  }

  private fun <T : Any> watch(
      uid: String,
      register: (EventListener<T>) -> ListenerRegistration,
  ): Flow<T> = callbackFlow {
    val listener =
        register(
            EventListener { value, error ->
              when {
                !sameSession(uid) -> close(failure(Code.UNAUTHENTICATED))
                error != null -> close(error)
                value != null -> trySend(value)
              }
            }
        )
    val session = FirebaseAuth.AuthStateListener {
      if (!sameSession(uid)) close(failure(Code.UNAUTHENTICATED))
    }
    auth.addAuthStateListener(session)
    awaitClose {
      listener.remove()
      auth.removeAuthStateListener(session)
    }
  }

  private fun sameSession(uid: String) = currentUserId() == uid && auth.currentUser?.uid == uid

  private fun guard(uid: String) {
    if (!sameSession(uid)) throw failure(Code.UNAUTHENTICATED)
  }

  private fun failure(code: Code) = FirebaseFirestoreException("Order chat operation failed", code)
}
