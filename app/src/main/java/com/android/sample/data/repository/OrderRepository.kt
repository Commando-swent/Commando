package com.android.sample.data.repository

// AI assistance: Claude Code (#147); OpenAI Codex (cancellation contract and KDoc).
import com.android.sample.model.Order
import com.android.sample.model.OrderDecision
import com.android.sample.model.OrderDraft
import com.android.sample.model.OrderStatus

/** Inject the SAME session-scoped repository into Review, My Orders and Order Details. */
interface OrderRepository {
  /** Observe current requester orders, retaining an unconfirmed state until server data arrives. */
  fun observeMyOrders(): kotlinx.coroutines.flow.Flow<OrderResult<List<Order>>> =
      kotlinx.coroutines.flow.flow { emit(getMyOrders()) }

  /** Derives requester identity from the session; does not clear the caller's draft. */
  suspend fun createOrder(draft: OrderDraft): OrderResult<Order>

  /**
   * Atomically edits the persisted requester's PENDING order on an open, upcoming trip. Items and
   * commission change with an incremented revision; identity, trip, creation time and status remain
   * immutable. A competing final transition returns AlreadyDecided with the stored order. Retrying
   * the same edit is safe; after an uncertain failure callers may reload by ID to reconcile server
   * state. An ended session returns NotAuthenticated even if the server already committed.
   * Concurrent edits of the same revision cannot overwrite each other: the loser receives
   * AlreadyDecided with the persisted winner. Identical retries are read-only.
   *
   * @param orderId Existing order document ID, never a document path.
   * @param draft Valid replacement items and commission with the original trip ID.
   * @param expectedRevision Required revision displayed when editing began; legacy orders have
   *   revision zero.
   * @return Updated order or a typed failure; totals are derived from the replacement items.
   * @throws kotlinx.coroutines.CancellationException If the caller is cancelled; a committed server
   *   write cannot be undone by cancelling the coroutine.
   */
  suspend fun editOrder(
      orderId: String,
      draft: OrderDraft,
      expectedRevision: Int,
  ): OrderResult<Order>

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
   * Trip owner only; [expectedRevision] is the revision displayed to the owner. A stale revision
   * returns AlreadyDecided with the latest snapshot before writing. Every caller must supply its
   * displayed revision, including zero for legacy unedited orders. Identical final decisions are
   * read-only; opposite decisions also return AlreadyDecided.
   */
  suspend fun decideOrder(
      orderId: String,
      decision: OrderDecision,
      expectedRevision: Int,
  ): OrderResult<Order>

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
