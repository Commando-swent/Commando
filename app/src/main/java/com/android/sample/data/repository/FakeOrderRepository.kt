package com.android.sample.data.repository

import com.android.sample.model.Order
import com.android.sample.model.OrderDraft
import com.android.sample.model.OrderStatus
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares orders within one instance only; never persists data or enforces Firebase security. */
class FakeOrderRepository(
    private val currentUserId: () -> String?,
    initialOrders: List<Order> = emptyList(),
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
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
            .sortedWith(compareByDescending<Order> { it.createdAt }.thenBy { it.id })
            .map { it.snapshot() }
    )
  }

  override suspend fun getOrderById(orderId: String): OrderResult<Order> = withSession { userId ->
    if (orderId.isBlank()) return@withSession OrderResult.Error(OrderError.InvalidData)
    val order = orders[orderId] ?: return@withSession OrderResult.Error(OrderError.NotFound)
    if (userId != order.requesterId) {
      return@withSession OrderResult.Error(OrderError.PermissionDenied)
    }
    OrderResult.Success(order.snapshot())
  }

  private suspend fun <T> withSession(action: (String) -> OrderResult<T>): OrderResult<T> {
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

  private fun OrderDraft.isValid(): Boolean {
    if (tripId.isBlank() || commissionCents < 0) return false
    if (items.isEmpty() || items.map { it.productId }.distinct().size != items.size) return false
    if (
        items.any {
          it.productId.isBlank() || it.name.isBlank() || it.quantity <= 0 || it.unitPriceCents < 0
        }
    )
        return false
    return try {
      val productTotal =
          items.fold(0) { total, item ->
            Math.addExact(
                total,
                Math.multiplyExact(item.unitPriceCents, item.quantity),
            )
          }
      Math.addExact(productTotal, commissionCents)
      true
    } catch (_: ArithmeticException) {
      false
    }
  }
}
