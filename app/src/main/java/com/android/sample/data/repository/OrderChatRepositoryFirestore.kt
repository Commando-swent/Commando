package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.firestore.FirebaseFirestoreException.Code
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
  ): OrderResult<Unit> {
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
      if (!orderId.isDocumentId() || !messageId.isDocumentId() || !text.isValidChatText())
          throw failure(Code.INVALID_ARGUMENT)
      val orderRef = order(orderId)
      val messageRef = orderRef.collection("messages").document(messageId)
      firestore
          .runTransaction { transaction ->
            context.ensureActive()
            guard(uid)
            writeOnce(transaction, orderRef, messageRef, uid, text)
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
  ) {
    val storedOrder = transaction.get(orderRef)
    if (!storedOrder.exists()) throw failure(Code.NOT_FOUND)
    val previous = transaction.get(messageRef)
    if (previous.exists()) {
      if (previous.getString("senderId") != uid || previous.getString("text") != text)
          throw failure(Code.INVALID_ARGUMENT)
    } else {
      val status = OrderStatus.valueOf(storedOrder.getString("status").orEmpty())
      if (!status.canSendChat) throw failure(Code.PERMISSION_DENIED)
      guard(uid)
      transaction.set(
          messageRef,
          mapOf(
              "senderId" to uid,
              "text" to text,
              "createdAt" to FieldValue.serverTimestamp(),
          ),
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
                          history.documents.reversed().map { document ->
                            ChatMessage(
                                document.id,
                                document.getString("senderId") ?: throw failure(Code.DATA_LOSS),
                                document.getString("text") ?: throw failure(Code.DATA_LOSS),
                                document.getTimestamp("createdAt")?.toInstant()
                                    ?: throw failure(Code.DATA_LOSS),
                            )
                          },
                      )
                  )
                }
            )
          }
          .catch { exception ->
            if (exception !is Exception) throw exception
            emit(OrderResult.Error(exception.toOrderError()))
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
