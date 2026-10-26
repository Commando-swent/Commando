package com.android.sample.data.repository

import com.android.sample.model.Order
import com.android.sample.model.OrderDraft
import com.android.sample.model.OrderStatus
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shares orders within one instance only; never persists data or enforces Firebase security. Inject
 * the shared [tripRepository] to enable associated-trip owner reads; absent lookup denies them.
 */
class FakeOrderRepository(
    private val currentUserId: () -> String?,
    initialOrders: List<Order> = emptyList(),
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val tripRepository: TripRepository? = null,
) : OrderRepository {
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

  override suspend fun getOrderById(orderId: String): OrderResult<Order> = withSession { userId ->
    if (orderId.isBlank()) return@withSession OrderResult.Error(OrderError.InvalidData)
    val order = orders[orderId] ?: return@withSession OrderResult.Error(OrderError.NotFound)
    if (userId != order.requesterId) {
      val error = tripOwnerAccessError(order.tripId, userId)
      if (error != null) return@withSession OrderResult.Error(error)
    }
    OrderResult.Success(order.snapshot())
  }

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
