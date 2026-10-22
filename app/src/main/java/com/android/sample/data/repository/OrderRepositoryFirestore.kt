package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.*
import com.google.firebase.Timestamp
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
  ) : this(FirebaseOrderFirestoreDataSource(firestore), currentUserId)

  private val submissions = Mutex()
  private val pending = mutableMapOf<Pair<String, OrderDraft>, String>()

  override suspend fun createOrder(draft: OrderDraft): OrderResult<Order> = submissions.withLock {
    session { uid ->
      val input = draft.copy(items = draft.items.toList())
      if (!input.isValid() || !input.tripId.isDocumentId())
          return@session failure(OrderError.InvalidData)
      val key = uid to input
      // Retain the ID across uncertain writes/confirmation failures, including cancellation.
      pending[key]?.let { id ->
        source.get(id)?.let { document ->
          val result = decode(document)
          if (result is OrderResult.Success && sameSession(uid)) pending.remove(key)
          return@session result
        }
      }
      val trip = source.getTrip(input.tripId) ?: return@session failure(OrderError.NotFound)
      if (!sameSession(uid)) return@session failure(OrderError.NotAuthenticated)
      val departure = trip["scheduledAt"] as? Timestamp
      if (trip["status"] != "PUBLISHED" || departure == null || departure.toInstant() <= now()) {
        return@session failure(OrderError.InvalidData)
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
      result
    }
  }

  override suspend fun getMyOrders(): OrderResult<List<Order>> = session { uid ->
    val orders =
        source.getMine(uid).map { it.toOrder() ?: return@session failure(OrderError.InvalidData) }
    if (orders.any { it.requesterId != uid }) return@session failure(OrderError.PermissionDenied)
    OrderResult.Success(
        orders.sortedWith(compareByDescending<Order> { it.createdAt }.thenBy { it.id })
    )
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
      failure(
          when ((exception as? FirebaseFirestoreException)?.code) {
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> OrderError.NotAuthenticated
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> OrderError.PermissionDenied
            FirebaseFirestoreException.Code.NOT_FOUND -> OrderError.NotFound
            FirebaseFirestoreException.Code.INVALID_ARGUMENT,
            FirebaseFirestoreException.Code.OUT_OF_RANGE,
            FirebaseFirestoreException.Code.DATA_LOSS -> OrderError.InvalidData
            else -> OrderError.Unknown
          }
      )
    }
  }

  private suspend fun sameSession(uid: String): Boolean {
    currentCoroutineContext().ensureActive()
    return currentUserId() == uid
  }
}

internal data class OrderFirestoreDocument(val id: String, val data: Map<String, Any?>)

internal interface OrderFirestoreDataSource {
  suspend fun create(id: String, data: Map<String, Any?>): OrderFirestoreDocument

  suspend fun get(id: String): OrderFirestoreDocument?

  suspend fun getMine(uid: String): List<OrderFirestoreDocument>

  suspend fun getTrip(id: String): Map<String, Any?>?
}

internal class FirebaseOrderFirestoreDataSource(private val instance: FirebaseFirestore? = null) :
    OrderFirestoreDataSource {
  private val firestore
    get() = instance ?: FirebaseFirestore.getInstance()

  private val orders
    get() = firestore.collection("orders")

  override suspend fun create(id: String, data: Map<String, Any?>): OrderFirestoreDocument {
    val ref = orders.document(id)
    ref.set(data).await()
    return checkNotNull(get(ref.id))
  }

  override suspend fun get(id: String): OrderFirestoreDocument? =
      orders
          .document(id)
          .get(Source.SERVER)
          .await()
          .takeIf { it.exists() }
          ?.let { OrderFirestoreDocument(it.id, it.data.orEmpty()) }

  override suspend fun getMine(uid: String) =
      orders.whereEqualTo("requesterId", uid).get(Source.SERVER).await().documents.map {
        OrderFirestoreDocument(it.id, it.data.orEmpty())
      }

  override suspend fun getTrip(id: String) =
      firestore
          .collection("trips")
          .document(id)
          .get(Source.SERVER)
          .await()
          .takeIf { it.exists() }
          ?.data
}

private fun failure(error: OrderError) = OrderResult.Error(error)

private fun decode(document: OrderFirestoreDocument): OrderResult<Order> =
    document.toOrder()?.let { OrderResult.Success(it) } ?: failure(OrderError.InvalidData)

private fun String.isDocumentId() = isNotBlank() && !contains('/') && this != "." && this != ".."

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
