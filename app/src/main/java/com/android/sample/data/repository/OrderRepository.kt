package com.android.sample.data.repository

// AI assistance: Claude Code (#147); OpenAI Codex (cancellation contract and KDoc).
import com.android.sample.model.Order
import com.android.sample.model.OrderDecision
import com.android.sample.model.OrderDraft
import com.android.sample.model.OrderStatus

/** Inject the SAME session-scoped repository into Review, My Orders and Order Details. */
interface OrderRepository {
  /** Derives requester identity from the session; does not clear the caller's draft. */
  suspend fun createOrder(draft: OrderDraft): OrderResult<Order>

  /**
   * Atomically edits the persisted requester's PENDING order on an open, upcoming trip. Only items
   * and commission change; identity, trip, creation time and status remain immutable. A competing
   * final transition returns AlreadyDecided with the stored order. Retrying the same edit is safe;
   * after an uncertain failure callers may reload by ID to reconcile server state. An ended session
   * returns NotAuthenticated even if the server already committed.
   *
   * @param orderId Existing order document ID, never a document path.
   * @param draft Valid replacement items and commission with the original trip ID.
   * @return Updated order or a typed failure; totals are derived from the replacement items.
   * @throws kotlinx.coroutines.CancellationException If the caller is cancelled; a committed server
   *   write cannot be undone by cancelling the coroutine.
   */
  suspend fun editOrder(orderId: String, draft: OrderDraft): OrderResult<Order>

  /** Current requester's orders, newest first; equal timestamps are ordered by ID. */
  suspend fun getMyOrders(): OrderResult<List<Order>>

  /**
   * Allows the requester or authenticated owner of the associated Trip; unrelated users are denied.
   */
  suspend fun getOrderById(orderId: String): OrderResult<Order>

  /**
   * Orders of [tripId] for the authenticated owner of that Trip, newest first; equal timestamps are
   * ordered by ID. Requesters and unrelated users are denied, even for orders they placed.
   */
  suspend fun getOrdersForTrip(tripId: String): OrderResult<List<Order>>

  /**
   * Trip owner only; identical final decisions are read-only, opposite ones return AlreadyDecided.
   */
  suspend fun decideOrder(orderId: String, decision: OrderDecision): OrderResult<Order>

  /**
   * Trip owner only, excluding self-orders; atomically changes only ACCEPTED to READY_FOR_HANDOFF.
   * Authorization precedes read-only repeats and AlreadyDecided conflicts containing persisted
   * state. Ended sessions cannot receive results; cancellation cannot undo a committed transition.
   */
  suspend fun markReadyForHandoff(orderId: String): OrderResult<Order>

  /**
   * Cancels the authenticated requester's pending order without deleting its history.
   *
   * Authorizes the persisted requester. Only [OrderStatus.PENDING] changes to
   * [OrderStatus.CANCELLED]; other fields stay immutable. Repeats succeed without writing;
   * conflicts return [OrderError.AlreadyDecided] with the persisted winner.
   *
   * @param orderId Firestore document ID; blank IDs and document paths are invalid.
   * @return Persisted order or typed failure, including [OrderError.NotAuthenticated] for ended
   *   sessions and [OrderError.PermissionDenied] for other users. Retry uncertain commits by ID.
   * @throws kotlinx.coroutines.CancellationException When the calling coroutine is cancelled.
   *   Cancelling the coroutine cannot undo a server commit.
   */
  suspend fun cancelOrder(orderId: String): OrderResult<Order>

  /**
   * Authenticated requester only; atomically changes READY_FOR_HANDOFF to DELIVERED, preserving
   * every other field. Authorized repeats at DELIVERED return Success without writing; other
   * statuses return AlreadyDecided with the persisted order. Authorization precedes both outcomes.
   */
  suspend fun confirmReceipt(orderId: String): OrderResult<Order>
}

/** Shared list order of every order query. */
internal val NEWEST_ORDER_FIRST: Comparator<Order> =
    compareByDescending<Order> { it.createdAt }.thenBy { it.id }

/** Owner operations only; requester cancellation needs its own authorization policy. */
internal enum class OwnerOrderTransition(val source: OrderStatus, val target: OrderStatus) {
  ACCEPT(OrderStatus.PENDING, OrderStatus.ACCEPTED),
  REJECT(OrderStatus.PENDING, OrderStatus.REJECTED),
  MARK_READY(OrderStatus.ACCEPTED, OrderStatus.READY_FOR_HANDOFF),
}

internal fun OrderDecision.ownerTransition(): OwnerOrderTransition =
    when (this) {
      OrderDecision.ACCEPT -> OwnerOrderTransition.ACCEPT
      OrderDecision.REJECT -> OwnerOrderTransition.REJECT
    }

internal fun OrderDecision.targetStatus(): OrderStatus = ownerTransition().target
