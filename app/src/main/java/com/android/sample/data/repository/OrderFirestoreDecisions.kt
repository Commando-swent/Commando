package com.android.sample.data.repository

// AI assistance: OpenAI Codex (#325).
import com.android.sample.model.*
import com.commando.app.model.TripStatus
import com.google.firebase.Timestamp
import java.time.Instant

/** Null revisions leave content-independent transitions unrestricted; legacy documents use zero. */
internal fun OrderFirestoreDocument.staleRevision(expected: Int?): Boolean =
    expected != null && revisionOrNull() != expected

/** Pure write eligibility, invoked only after the transaction authorizes the persisted order. */
internal fun OrderFirestoreDocument.canTransition(
    from: OrderStatus,
    expectedRevision: Int?,
): Boolean = data["status"] == from.name && !staleRevision(expectedRevision)

/** A denied edit is recoverable only when persisted status or revision actually changed. */
internal fun OrderFirestoreDocument.hasEditConflict(expectedRevision: Int): Boolean =
    data["status"] != OrderStatus.PENDING.name || staleRevision(expectedRevision)

/**
 * Classifies an authorized transition response: target is an idempotent success, an unchanged
 * source is invalid unless its revision changed, and competing statuses return the persisted
 * snapshot. Identity checks and denied-write reconciliation remain in the repository/data source.
 */
internal fun Order.transitionResult(
    from: OrderStatus,
    target: OrderStatus,
    expectedRevision: Int?,
): OrderResult<Order> =
    when {
      status == from && (expectedRevision == null || revision == expectedRevision) ->
          OrderResult.Error(OrderError.InvalidData)
      status == target -> OrderResult.Success(this)
      else -> OrderResult.Error(OrderError.AlreadyDecided(this))
    }

/** Classifies an edit response after the repository has validated its order/requester/trip IDs. */
internal fun Order.editResult(draft: OrderDraft, expectedRevision: Int): OrderResult<Order> =
    when {
      status != OrderStatus.PENDING -> OrderResult.Error(OrderError.AlreadyDecided(this))
      items == draft.items && commissionCents == draft.commissionCents -> OrderResult.Success(this)
      revision != expectedRevision -> OrderResult.Error(OrderError.AlreadyDecided(this))
      else -> OrderResult.Error(OrderError.InvalidData)
    }

/** Identical content is read-only even at MAX_VALUE; changing it must never overflow revision. */
internal fun Order.nextEditRevision(draft: OrderDraft): OrderResult<Int?> =
    when {
      items == draft.items && commissionCents == draft.commissionCents -> OrderResult.Success(null)
      revision == Int.MAX_VALUE -> OrderResult.Error(OrderError.InvalidData)
      else -> OrderResult.Success(revision + 1)
    }

/**
 * Evaluates the existing transaction's edit-trip policy from raw fields and an explicit clock.
 * Nullable casts intentionally keep the SDK getters' failure category for malformed field types.
 * Checks short-circuit: an owned/non-published trip never inspects its departure. This is not an
 * authorization boundary; the transaction must first authorize the stored requester and read trip.
 *
 * Known legacy limitation: a missing ownerId passes the non-self-order check when the status and
 * departure are eligible. Preserve this behavior in this refactor; rejecting incomplete trip
 * documents consistently across creation/editing needs a separate validation change and tests.
 */
internal fun Map<String, Any?>.isEditableOrderTrip(uid: String, now: Instant): Boolean =
    this["ownerId"] as String? != uid &&
        this["status"] as String? == TripStatus.PUBLISHED.name &&
        (this["scheduledAt"] as Timestamp?)?.toInstant()?.isAfter(now) == true
