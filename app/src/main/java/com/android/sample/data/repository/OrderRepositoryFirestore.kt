package com.android.sample.data.repository

// AI assistance: OpenAI Codex; trip-owner query (#147), error mapping (#193): Claude Code.
import com.android.sample.model.*
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Server-confirmed orders. Inject the session-bound identity supplied by SessionOrderViewModel. */
class OrderRepositoryFirestore
internal constructor(
    private val source: OrderFirestoreDataSource,
    private val currentUserId: () -> String?,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : OrderRepository {
  constructor(
      currentUserId: () -> String?,
      firestore: FirebaseFirestore? = null,
  ) : this(FirebaseOrderFirestoreDataSource(firestore, currentUserId), currentUserId)

  private val submissions = Mutex()
  private val pending = mutableMapOf<Pair<String, OrderDraft>, String>()

  override suspend fun createOrder(draft: OrderDraft): OrderResult<Order> = submissions.withLock {
    session { uid -> createForSession(draft, uid) }
  }

  private suspend fun createForSession(draft: OrderDraft, uid: String): OrderResult<Order> {
    val input = draft.copy(items = draft.items.toList())
    if (!input.isValid() || !input.tripId.isDocumentId()) return failure(OrderError.InvalidData)
    val key = uid to input
    // Retain the ID across uncertain writes/confirmation failures, including cancellation.
    pending[key]?.let { id ->
      source.get(id)?.let { document ->
        val result = decode(document)
        if (result is OrderResult.Success && sameSession(uid)) pending.remove(key)
        return result
      }
    }
    val trip = source.getTrip(input.tripId) ?: return failure(OrderError.NotFound)
    if (!sameSession(uid)) return failure(OrderError.NotAuthenticated)
    val departure = trip["scheduledAt"] as? Timestamp
    if (
        trip["ownerId"] == uid ||
            trip["status"] != "PUBLISHED" ||
            departure == null ||
            departure.toInstant() <= now()
    ) {
      return failure(OrderError.InvalidData)
    }
    val id = pending.getOrPut(key, newId)
    val result =
        decode(
            source.create(
                id,
                mapOf(
                    "tripId" to input.tripId,
                    "requesterId" to uid,
                    "items" to
                        input.items.map {
                          mapOf(
                              "productId" to it.productId,
                              "name" to it.name,
                              "unitPriceCents" to it.unitPriceCents,
                              "quantity" to it.quantity,
                          )
                        },
                    "commissionCents" to input.commissionCents,
                    "status" to OrderStatus.PENDING.name,
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            )
        )
    if (result is OrderResult.Success && sameSession(uid)) pending.remove(key)
    return result
  }

  override suspend fun getMyOrders(): OrderResult<List<Order>> = session { uid ->
    val orders =
        source.getMine(uid).map { it.toOrder() ?: return@session failure(OrderError.InvalidData) }
    if (orders.any { it.requesterId != uid }) return@session failure(OrderError.PermissionDenied)
    OrderResult.Success(orders.sortedWith(NEWEST_ORDER_FIRST))
  }

  override suspend fun getOrderById(orderId: String): OrderResult<Order> = session { uid ->
    if (!orderId.isDocumentId()) return@session failure(OrderError.InvalidData)
    val document = source.get(orderId) ?: return@session failure(OrderError.NotFound)
    if (!sameSession(uid)) return@session failure(OrderError.NotAuthenticated)
    val order = document.toOrder() ?: return@session failure(OrderError.InvalidData)
    if (order.requesterId != uid && source.getTrip(order.tripId)?.get("ownerId") != uid) {
      return@session failure(OrderError.PermissionDenied)
    }
    OrderResult.Success(order)
  }

  /** Checks trip ownership, then reads the trip's orders from the server; see the interface. */
  override suspend fun getOrdersForTrip(tripId: String): OrderResult<List<Order>> = session { uid ->
    if (!tripId.isDocumentId()) return@session failure(OrderError.InvalidData)
    // Rules enforce ownership independently; this check also covers a readable PUBLISHED trip.
    if (source.getTrip(tripId)?.get("ownerId") != uid) {
      return@session failure(OrderError.PermissionDenied)
    }
    if (!sameSession(uid)) return@session failure(OrderError.NotAuthenticated)
    val documents = source.getForTrip(tripId)
    val orders = documents.map { it.toOrder() ?: return@session failure(OrderError.InvalidData) }
    if (orders.any { it.tripId != tripId }) return@session failure(OrderError.PermissionDenied)
    OrderResult.Success(orders.sortedWith(NEWEST_ORDER_FIRST))
  }

  override suspend fun decideOrder(
      orderId: String,
      decision: OrderDecision,
  ): OrderResult<Order> =
      transitionOrder(orderId, decision.ownerTransition()) { uid ->
        source.decide(orderId, uid, decision)
      }

  override suspend fun markReadyForHandoff(orderId: String): OrderResult<Order> =
      transitionOrder(orderId, OwnerOrderTransition.MARK_READY) { uid ->
        source.markReadyForHandoff(orderId, uid)
      }

  /** Confirms receipt for the persisted requester; see [OrderRepository.confirmReceipt]. */
  override suspend fun confirmReceipt(orderId: String): OrderResult<Order> =
      transitionOrder(orderId, OrderStatus.READY_FOR_HANDOFF, OrderStatus.DELIVERED) { uid ->
        source.confirmReceipt(orderId, uid)
      }

  private suspend fun transitionOrder(
      orderId: String,
      transition: OwnerOrderTransition,
      update: suspend (String) -> OrderFirestoreDocument,
  ): OrderResult<Order> = transitionOrder(orderId, transition.source, transition.target, update)

  private suspend fun transitionOrder(
      orderId: String,
      from: OrderStatus,
      target: OrderStatus,
      update: suspend (String) -> OrderFirestoreDocument,
  ): OrderResult<Order> = session { uid ->
    if (!orderId.isDocumentId()) return@session failure(OrderError.InvalidData)
    val order = update(uid).toOrder() ?: return@session failure(OrderError.InvalidData)
    if (order.id != orderId || order.status == from) {
      return@session failure(OrderError.InvalidData)
    }
    if (order.status == target) OrderResult.Success(order)
    else failure(OrderError.AlreadyDecided(order))
  }

  override suspend fun cancelOrder(orderId: String): OrderResult<Order> =
      transitionOrder(orderId, OrderStatus.PENDING, OrderStatus.CANCELLED) { uid ->
        source.cancel(orderId, uid)
      }

  private suspend fun <T> session(action: suspend (String) -> OrderResult<T>): OrderResult<T> {
    currentCoroutineContext().ensureActive()
    val uid =
        currentUserId()?.takeIf { it.isNotBlank() } ?: return failure(OrderError.NotAuthenticated)
    return try {
      val result = action(uid)
      if (sameSession(uid)) result else failure(OrderError.NotAuthenticated)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (exception: Exception) {
      currentCoroutineContext().ensureActive()
      if (!sameSession(uid)) return failure(OrderError.NotAuthenticated)
      failure(exception.toOrderError())
    }
  }

  private suspend fun sameSession(uid: String): Boolean {
    currentCoroutineContext().ensureActive()
    return currentUserId() == uid
  }
}

/** Order transport alias; [toOrder] validates the shared raw document against the order schema. */
internal typealias OrderFirestoreDocument = FirestoreDocument

/**
 * Persistence boundary for raw order snapshots; repositories validate input and decoded results.
 *
 * Reads use the server. Transactions authorize owners against the latest order/trip, and authorize
 * cancellation and receipt confirmation against the persisted requester. Implementations throw
 * storage failures; the repository maps them after checking the active session.
 */
internal interface OrderFirestoreDataSource {
  /** Writes the order and placement event atomically, then confirms the server snapshot. */
  suspend fun create(id: String, data: Map<String, Any?>): OrderFirestoreDocument

  /** Server snapshot, or null for a readable missing document; denied reads still throw. */
  suspend fun get(id: String): OrderFirestoreDocument?

  /** Server query filtered by the persisted requester; the repository applies shared ordering. */
  suspend fun getMine(uid: String): List<OrderFirestoreDocument>

  /** Server read of every order whose `tripId` is [tripId]; the rules restrict it to the owner. */
  suspend fun getForTrip(tripId: String): List<OrderFirestoreDocument>

  /** Server trip fields used for creation eligibility and associated-owner authorization. */
  suspend fun getTrip(id: String): Map<String, Any?>?

  /** Atomically authorizes the owner and publishes a decision event only for a new decision. */
  suspend fun decide(id: String, ownerId: String, decision: OrderDecision): OrderFirestoreDocument

  /** Owner-only ACCEPTED → READY_FOR_HANDOFF; repeats return a stored snapshot without writing. */
  suspend fun markReadyForHandoff(id: String, ownerId: String): OrderFirestoreDocument

  /** Authorizes the stored requester before atomically cancelling or returning a stored outcome. */
  suspend fun cancel(id: String, requesterId: String): OrderFirestoreDocument

  /** Authorizes the persisted requester before returning any transition outcome. */
  suspend fun confirmReceipt(id: String, requesterId: String): OrderFirestoreDocument
}

/**
 * Firebase implementation of [OrderFirestoreDataSource], preserving server reads and atomic events.
 *
 * @param instance Firebase client; null resolves the default application lazily.
 * @param currentUserId Optional session-bound identity checked alongside Firebase Auth on retries.
 */
internal class FirebaseOrderFirestoreDataSource(
    private val instance: FirebaseFirestore? = null,
    private val currentUserId: (() -> String?)? = null,
) : OrderFirestoreDataSource {
  private val firestore
    get() = instance ?: FirebaseFirestore.getInstance()

  private val orders
    get() = firestore.collection("orders")

  override suspend fun create(id: String, data: Map<String, Any?>): OrderFirestoreDocument {
    val ref = orders.document(id)
    val trip = getTrip(data.getValue("tripId") as String)
    val recipient =
        trip?.get("ownerId") as? String
            ?: throw FirebaseFirestoreException(
                "Order creation failed",
                FirebaseFirestoreException.Code.NOT_FOUND,
            )
    firestore
        .runBatch { batch ->
          batch.set(ref, data)
          batch.setEvent(id, recipient, NotificationEventType.ORDER_PLACED)
        }
        .await()
    return checkNotNull(get(ref.id))
  }

  override suspend fun get(id: String): OrderFirestoreDocument? =
      orders
          .document(id)
          .get(Source.SERVER)
          .await()
          .takeIf { it.exists() }
          ?.let { it.toFirestoreDocument() }

  override suspend fun getMine(uid: String) = whereEqualTo("requesterId", uid)

  override suspend fun getForTrip(tripId: String) = whereEqualTo("tripId", tripId)

  private suspend fun whereEqualTo(field: String, value: String) =
      orders.whereEqualTo(field, value).get(Source.SERVER).await().documents.map {
        it.toFirestoreDocument()
      }

  override suspend fun getTrip(id: String) =
      firestore
          .collection("trips")
          .document(id)
          .get(Source.SERVER)
          .await()
          .takeIf { it.exists() }
          ?.data

  override suspend fun decide(
      id: String,
      ownerId: String,
      decision: OrderDecision,
  ): OrderFirestoreDocument = transitionOrder(id, ownerId, decision.ownerTransition())

  override suspend fun markReadyForHandoff(id: String, ownerId: String): OrderFirestoreDocument =
      transitionOrder(id, ownerId, OwnerOrderTransition.MARK_READY)

  override suspend fun confirmReceipt(id: String, requesterId: String): OrderFirestoreDocument =
      transitionOrder(id, requesterId, OrderStatus.READY_FOR_HANDOFF, OrderStatus.DELIVERED, true)

  private suspend fun transitionOrder(
      id: String,
      ownerId: String,
      transition: OwnerOrderTransition,
  ): OrderFirestoreDocument =
      transitionOrder(
          id,
          ownerId,
          transition.source,
          transition.target,
          ownerTransition = transition,
      )

  private suspend fun transitionOrder(
      id: String,
      uid: String,
      from: OrderStatus,
      target: OrderStatus,
      requester: Boolean = false,
      ownerTransition: OwnerOrderTransition? = null,
  ): OrderFirestoreDocument {
    val context = currentCoroutineContext()
    val database = firestore
    // Every transition verifies Firebase identity and the session-bound repository identity.
    fun ensureSession() {
      context.ensureActive()
      ensureDecisionIdentity(database.app, uid)
    }
    ensureSession()
    fun attempt(write: Boolean) = database.runTransaction { transaction ->
      ensureSession()
      val ref = database.collection("orders").document(id)
      val document = transaction.readOrder(ref, uid, requester)
      // Retried callbacks authorize current persisted data before writing.
      ensureSession()
      if (write && document.data["status"] == from.name) {
        val status = target.name
        transaction.update(ref, "status", status)
        ownerTransition?.let { transaction.setDecisionEvent(ref, document, it) }
        document.copy(data = document.data + ("status" to status))
      } else document
    }
    return try {
      attempt(write = true).await()
    } catch (denied: FirebaseFirestoreException) {
      if (denied.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw denied
      // Reconcile a competing commit without retrying a denied write or bypassing authorization.
      attempt(write = false).await().also {
        ensureSession()
        if (it.data["status"] == from.name) throw denied
      }
    }
  }

  // Cancellation changes status only; order placement and decisions retain their atomic events.
  override suspend fun cancel(id: String, requesterId: String): OrderFirestoreDocument =
      transitionOrder(id, requesterId, OrderStatus.PENDING, OrderStatus.CANCELLED, requester = true)

  /** Verifies Firebase and session identities on each transaction attempt. */
  private fun ensureDecisionIdentity(app: FirebaseApp, uid: String) {
    if (
        FirebaseAuth.getInstance(app).currentUser?.uid != uid ||
            (currentUserId != null && currentUserId.invoke() != uid)
    ) {
      throw transitionError(FirebaseFirestoreException.Code.UNAUTHENTICATED)
    }
  }

  private fun Transaction.readOrder(
      ref: DocumentReference,
      uid: String,
      requester: Boolean,
  ): OrderFirestoreDocument {
    val stored = get(ref)
    if (!stored.exists()) throw transitionError(FirebaseFirestoreException.Code.NOT_FOUND)
    val document = stored.toFirestoreDocument()
    val order =
        document.toOrder()
            ?: throw transitionError(FirebaseFirestoreException.Code.INVALID_ARGUMENT)
    val authorized =
        if (requester) {
          order.requesterId == uid
        } else {
          val trip = get(ref.firestore.collection("trips").document(order.tripId))
          trip.exists() && trip.getString("ownerId") == uid && order.requesterId != uid
        }
    if (!authorized) {
      throw transitionError(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    }
    return document
  }

  private fun transitionError(code: FirebaseFirestoreException.Code) =
      FirebaseFirestoreException("Order transition failed", code)

  private fun Transaction.setDecisionEvent(
      ref: DocumentReference,
      order: OrderFirestoreDocument,
      transition: OwnerOrderTransition,
  ) {
    val type =
        when (transition) {
          OwnerOrderTransition.ACCEPT -> NotificationEventType.ORDER_ACCEPTED
          OwnerOrderTransition.REJECT -> NotificationEventType.ORDER_REJECTED
          OwnerOrderTransition.MARK_READY -> return
        }
    set(
        ref.firestore.collection(NotificationFirestoreData.EVENTS).document(type.eventId(ref.id)),
        publishedEvent(ref.id, order.data.getValue("requesterId") as String, type),
    )
  }

  private fun WriteBatch.setEvent(id: String, recipient: String, type: NotificationEventType) {
    set(
        firestore.collection(NotificationFirestoreData.EVENTS).document(type.eventId(id)),
        publishedEvent(id, recipient, type),
    )
  }

  private fun publishedEvent(id: String, recipient: String, type: NotificationEventType) =
      mapOf(
          NotificationFirestoreData.ORDER_ID to id,
          NotificationFirestoreData.RECIPIENT_ID to recipient,
          NotificationFirestoreData.TYPE to type.name,
          NotificationFirestoreData.OCCURRED_AT to FieldValue.serverTimestamp(),
      )
}

private fun failure(error: OrderError) = OrderResult.Error(error)

/**
 * Maps the shared classifier from #209 to Order's existing errors, also used by notifications and
 * checklist persistence. Network/IO failures remain Unknown; wrapped causes are not unwrapped.
 * Coroutine cancellation must be rethrown by the calling session guard before reaching this mapper.
 */
internal fun Exception.toOrderError(): OrderError = toFirestoreFailure().toOrderError()

/** Keeps Order-specific outcomes separate from Trip/Product, including authentication failures. */
internal fun FirestoreFailure.toOrderError(): OrderError =
    when (this) {
      FirestoreFailure.UNAUTHENTICATED -> OrderError.NotAuthenticated
      FirestoreFailure.PERMISSION_DENIED -> OrderError.PermissionDenied
      FirestoreFailure.NOT_FOUND -> OrderError.NotFound
      FirestoreFailure.INVALID_DATA -> OrderError.InvalidData
      FirestoreFailure.NETWORK_UNAVAILABLE,
      FirestoreFailure.UNKNOWN -> OrderError.Unknown
    }

private fun decode(document: OrderFirestoreDocument): OrderResult<Order> =
    document.toOrder()?.let { OrderResult.Success(it) } ?: failure(OrderError.InvalidData)

private fun Any?.cents(): Int? =
    when (this) {
      is Long -> takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
      is Int -> takeIf { it >= 0 }
      else -> null
    }

internal fun OrderFirestoreDocument.toOrder(): Order? {
  val trip = data["tripId"] as? String ?: return null
  val requester = data["requesterId"] as? String ?: return null
  val fee = data["commissionCents"].cents() ?: return null
  val status = OrderStatus.entries.find { it.name == data["status"] } ?: return null
  val created = data["createdAt"] as? Timestamp ?: return null
  val items =
      (data["items"] as? List<*>)?.map { entry ->
        val item = entry as? Map<*, *> ?: return null
        OrderItem(
            item["productId"] as? String ?: return null,
            item["name"] as? String ?: return null,
            item["unitPriceCents"].cents() ?: return null,
            item["quantity"].cents() ?: return null,
        )
      } ?: return null
  if (
      !id.isDocumentId() ||
          !trip.isDocumentId() ||
          requester.isBlank() ||
          !OrderDraft(trip, items, fee).isValid()
  )
      return null
  return Order(id, trip, requester, items.toList(), fee, status, created.toInstant())
}
