package com.android.sample.data.repository

// AI assistance: OpenAI Codex (requester cancellation, pending-order editing and live feeds).
// AI assistance: Claude Code for the trip-owner query (#147).
// AI assistance: Claude Code for refusing orders on non-published trips (#233).
import com.android.sample.model.Order
import com.android.sample.model.OrderDecision
import com.android.sample.model.OrderDraft
import com.android.sample.model.OrderStatus
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shares orders within one instance only; never persists data or enforces Firebase security. Inject
 * the shared [tripRepository] to enable owner reads and reject self-orders during creation. Without
 * that lookup, creation retains standalone fixture behavior and owner reads are denied.
 */
class FakeOrderRepository(
    private val currentUserId: () -> String?,
    initialOrders: List<Order> = emptyList(),
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val tripRepository: TripRepository? = null,
) : OrderRepository {
  private val changes = MutableStateFlow(0L)

  override fun observeMyOrders() = changes.map { getMyOrders() }

  private val mutex = Mutex()
  private val orders = linkedMapOf<String, Order>()

  /** Deterministic failure injection: authenticated operations fail before accessing storage. */
  @Volatile var forcedError: OrderError? = null

  init {
    initialOrders.forEach { order ->
      require(order.id.isNotBlank() && order.requesterId.isNotBlank())
      require(OrderDraft(order.tripId, order.items, order.commissionCents).isValid())
      require(!orders.containsKey(order.id)) { "Duplicate fixture order ID" }
      orders[order.id] = order.snapshot()
    }
  }

  override suspend fun createOrder(draft: OrderDraft): OrderResult<Order> = withSession { userId ->
    val input = draft.copy(items = draft.items.toList())
    if (!input.isValid()) return@withSession OrderResult.Error(OrderError.InvalidData)
    val tripError = creationTripError(input.tripId, userId)
    if (tripError != null) return@withSession OrderResult.Error(tripError)
    val id = newId()
    if (id.isBlank() || orders.containsKey(id)) {
      return@withSession OrderResult.Error(OrderError.Unknown)
    }
    val order =
        Order(
            id = id,
            requesterId = userId,
            tripId = input.tripId,
            items = input.items,
            commissionCents = input.commissionCents,
            status = OrderStatus.PENDING,
            createdAt = now(),
        )
    orders[id] = order
    changes.value += 1
    OrderResult.Success(order.snapshot())
  }

  override suspend fun getMyOrders(): OrderResult<List<Order>> = withSession { userId ->
    OrderResult.Success(
        orders.values
            .filter { it.requesterId == userId }
            .sortedWith(NEWEST_ORDER_FIRST)
            .map { it.snapshot() }
    )
  }

  /** Editing requires a trip lookup, unlike standalone creation fixtures. */
  override suspend fun editOrder(
      orderId: String,
      draft: OrderDraft,
      expectedRevision: Int,
  ): OrderResult<Order> = withSession { uid ->
    val input = draft.copy(items = draft.items.toList())
    if (!orderId.isDocumentId() || !input.isValid() || expectedRevision < 0)
        return@withSession OrderResult.Error(OrderError.InvalidData)
    val stored = orders[orderId] ?: return@withSession OrderResult.Error(OrderError.NotFound)
    if (stored.requesterId != uid) return@withSession OrderResult.Error(OrderError.PermissionDenied)
    if (stored.tripId != input.tripId) return@withSession OrderResult.Error(OrderError.InvalidData)
    if (stored.status != OrderStatus.PENDING)
        return@withSession OrderResult.Error(OrderError.AlreadyDecided(stored.snapshot()))
    val tripError = editingTripError(stored.tripId, uid)
    if (tripError != null) return@withSession OrderResult.Error(tripError)
    stored.applyEdit(input, expectedRevision)
  }

  /** Applies an authorized edit under the repository mutex; identical retries remain read-only. */
  private fun Order.applyEdit(input: OrderDraft, expectedRevision: Int): OrderResult<Order> {
    if (items == input.items && commissionCents == input.commissionCents)
        return OrderResult.Success(snapshot())
    if (revision != expectedRevision)
        return OrderResult.Error(OrderError.AlreadyDecided(snapshot()))
    if (revision == Int.MAX_VALUE) return OrderResult.Error(OrderError.InvalidData)
    val updated =
        copy(items = input.items, commissionCents = input.commissionCents, revision = revision + 1)
    orders[id] = updated
    changes.value += 1
    return OrderResult.Success(updated.snapshot())
  }

  /** Fails closed when the associated trip cannot be verified in the same session. */
  private suspend fun editingTripError(tripId: String, uid: String): OrderError? {
    val result = lookupTrip(tripId, uid)
    if (result is OrderResult.Error)
        return when (result.error) {
          OrderError.NotAuthenticated,
          OrderError.Unknown -> result.error
          else -> OrderError.InvalidData
        }
    val trip = (result as? OrderResult.Success)?.data
    return if (
        trip == null ||
            trip.id != tripId ||
            trip.ownerId == uid ||
            trip.status != TripStatus.PUBLISHED ||
            trip.scheduledAt <= now()
    )
        OrderError.InvalidData
    else null
  }

  override suspend fun getOrderById(orderId: String): OrderResult<Order> = withSession { userId ->
    if (orderId.isBlank()) return@withSession OrderResult.Error(OrderError.InvalidData)
    val order = orders[orderId] ?: return@withSession OrderResult.Error(OrderError.NotFound)
    if (userId != order.requesterId) {
      val error = tripOwnerAccessError(order.tripId, userId)
      if (error != null) return@withSession OrderResult.Error(error)
    }
    OrderResult.Success(order.snapshot())
  }

  /** Same contract as [OrderRepository.getOrdersForTrip]; ownership comes from [tripRepository]. */
  override suspend fun getOrdersForTrip(tripId: String): OrderResult<List<Order>> =
      withSession { userId ->
        if (tripId.isBlank()) return@withSession OrderResult.Error(OrderError.InvalidData)
        val error = tripOwnerAccessError(tripId, userId)
        if (error != null) return@withSession OrderResult.Error(error)
        OrderResult.Success(
            orders.values
                .filter { it.tripId == tripId }
                .sortedWith(NEWEST_ORDER_FIRST)
                .map { it.snapshot() }
        )
      }

  override suspend fun decideOrder(
      orderId: String,
      decision: OrderDecision,
      expectedRevision: Int,
  ): OrderResult<Order> = transitionOrder(orderId, decision.ownerTransition(), expectedRevision)

  override suspend fun markReadyForHandoff(orderId: String): OrderResult<Order> =
      transitionOrder(orderId, OwnerOrderTransition.MARK_READY)

  override suspend fun confirmReceipt(orderId: String): OrderResult<Order> = withSession { userId ->
    if (!orderId.isDocumentId()) return@withSession OrderResult.Error(OrderError.InvalidData)
    val order = orders[orderId] ?: return@withSession OrderResult.Error(OrderError.NotFound)
    if (order.requesterId != userId) {
      return@withSession OrderResult.Error(OrderError.PermissionDenied)
    }
    val persisted =
        if (order.status == OrderStatus.READY_FOR_HANDOFF) {
          order.copy(status = OrderStatus.DELIVERED).also {
            orders[orderId] = it
            changes.value += 1
          }
        } else order
    val snapshot = persisted.snapshot()
    if (snapshot.status == OrderStatus.DELIVERED) OrderResult.Success(snapshot)
    else OrderResult.Error(OrderError.AlreadyDecided(snapshot))
  }

  private suspend fun transitionOrder(
      orderId: String,
      transition: OwnerOrderTransition,
      expectedRevision: Int? = null,
  ): OrderResult<Order> = withSession { userId ->
    if (!orderId.isDocumentId()) return@withSession OrderResult.Error(OrderError.InvalidData)
    val order = orders[orderId] ?: return@withSession OrderResult.Error(OrderError.NotFound)
    // Requester access never bypasses trip ownership, including for final decisions.
    val error = tripOwnerAccessError(order.tripId, userId)
    currentCoroutineContext().ensureActive()
    if (currentUserId() != userId) return@withSession OrderResult.Error(OrderError.NotAuthenticated)
    if (error != null || order.requesterId == userId) {
      return@withSession OrderResult.Error(error ?: OrderError.PermissionDenied)
    }
    val revisionError = order.decisionRevisionError(transition.source, expectedRevision)
    if (revisionError != null) return@withSession OrderResult.Error(revisionError)
    val persisted =
        if (order.status == transition.source) {
          order.copy(status = transition.target).also {
            orders[orderId] = it
            changes.value += 1
          }
        } else order
    val snapshot = persisted.snapshot()
    if (snapshot.status == transition.target) OrderResult.Success(snapshot)
    else OrderResult.Error(OrderError.AlreadyDecided(snapshot))
  }

  /** Checks displayed content only before a new decision, preserving authorized final retries. */
  private fun Order.decisionRevisionError(from: OrderStatus, expected: Int?): OrderError? =
      when {
        expected == null -> null
        expected < 0 -> OrderError.InvalidData
        status == from && revision != expected -> OrderError.AlreadyDecided(snapshot())
        else -> null
      }

  override suspend fun cancelOrder(orderId: String): OrderResult<Order> = withSession { userId ->
    if (!orderId.isDocumentId()) return@withSession OrderResult.Error(OrderError.InvalidData)
    val order = orders[orderId] ?: return@withSession OrderResult.Error(OrderError.NotFound)
    if (order.requesterId != userId) {
      return@withSession OrderResult.Error(OrderError.PermissionDenied)
    }
    currentCoroutineContext().ensureActive()
    if (currentUserId() != userId) return@withSession OrderResult.Error(OrderError.NotAuthenticated)
    val persisted =
        if (order.status == OrderStatus.PENDING) {
          order.copy(status = OrderStatus.CANCELLED).also {
            orders[orderId] = it
            changes.value += 1
          }
        } else order
    val snapshot = persisted.snapshot()
    if (snapshot.status == OrderStatus.CANCELLED) OrderResult.Success(snapshot)
    else OrderResult.Error(OrderError.AlreadyDecided(snapshot))
  }

  private suspend fun creationTripError(tripId: String, userId: String): OrderError? {
    return when (val result = lookupTrip(tripId, userId)) {
      null -> null
      is OrderResult.Error -> result.error
      is OrderResult.Success ->
          if (
              result.data.id != tripId ||
                  result.data.ownerId == userId ||
                  result.data.status != TripStatus.PUBLISHED
          )
              OrderError.InvalidData
          else null
    }
  }

  /** Shared lookup checks the session after suspension and preserves cancellation. */
  private suspend fun lookupTrip(tripId: String, uid: String): OrderResult<Trip>? {
    val source = tripRepository ?: return null
    val result =
        try {
          source.getTripById(tripId)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          currentCoroutineContext().ensureActive()
          return OrderResult.Error(
              if (currentUserId() != uid) OrderError.NotAuthenticated else OrderError.Unknown
          )
        }
    currentCoroutineContext().ensureActive()
    if (currentUserId() != uid) return OrderResult.Error(OrderError.NotAuthenticated)
    return when (result) {
      is TripResult.Success -> OrderResult.Success(result.data)
      is TripResult.Error ->
          OrderResult.Error(
              when (result.error) {
                TripError.NotFound -> OrderError.NotFound
                TripError.PermissionDenied -> OrderError.PermissionDenied
                else -> OrderError.Unknown
              }
          )
    }
  }

  private suspend fun tripOwnerAccessError(tripId: String, userId: String): OrderError? {
    // Resolve ownership from the associated Trip, never from UI role or caller-supplied owner ID.
    val trip =
        try {
          tripRepository?.getTripById(tripId)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          currentCoroutineContext().ensureActive()
          return OrderError.Unknown
        }
    currentCoroutineContext().ensureActive()
    val associatedTrip =
        when (trip) {
          is TripResult.Success -> trip.data
          else -> return OrderError.PermissionDenied
        }
    // A lookup can suspend while the authenticated account changes.
    return if (
        currentUserId() != userId || associatedTrip.id != tripId || associatedTrip.ownerId != userId
    ) {
      OrderError.PermissionDenied
    } else {
      null
    }
  }

  private suspend fun <T> withSession(action: suspend (String) -> OrderResult<T>): OrderResult<T> {
    currentCoroutineContext().ensureActive()
    return mutex.withLock {
      currentCoroutineContext().ensureActive()
      val userId = currentUserId()
      val error = forcedError
      when {
        userId.isNullOrBlank() -> OrderResult.Error(OrderError.NotAuthenticated)
        error != null -> OrderResult.Error(error)
        else -> action(userId)
      }
    }
  }

  private fun Order.snapshot() = copy(items = items.toList())
}
