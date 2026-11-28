package com.commando.app.data.repository

// AI assistance: OpenAI Codex.
import com.commando.app.model.OrderDecision
import com.commando.app.model.OrderNegotiation
import com.commando.app.model.OrderTerms

/**
 * Versioned negotiation boundary for #485/#487. Implementations derive the caller from the session
 * and authorize the persisted participants. Reads return server-confirmed state, not SDK overlays.
 * Initial terms come from the order fee and trip handoff (revision zero, requester proposer).
 *
 * Proposals and decisions atomically compare [com.commando.app.model.OrderOffer.revision]. Stale
 * revisions return [OrderError.AlreadyDecided]; callers reload the authoritative offer. Operation
 * IDs bind the original actor, revision and payload: retries are idempotent, reuse of an ID with a
 * different payload is InvalidData. Check an operation's receipt before rejecting its old revision;
 * an idempotent replay returns the CURRENT aggregate, not a stale acknowledgement snapshot. Only
 * the next participant may counter, accept or decline.
 *
 * Acceptance freezes the terms and updates order fee/status and existing lifecycle side effects in
 * the SAME transaction. The returned order is the sole price source for payment. An owner may
 * directly accept revision zero. Declining closes the order. Edits, cancellation or a competing
 * response must invalidate stale revisions. Once agreed or purchased, terms cannot change.
 *
 * Unknown outcomes may have committed: reload, and retry only the identical operation ID/payload.
 * Never return another account’s state after a session change. CancellationException propagates.
 */
interface OrderNegotiationRepository {
  suspend fun get(orderId: String): OrderResult<OrderNegotiation>

  suspend fun propose(
      orderId: String,
      operationId: String,
      expectedRevision: Int,
      terms: OrderTerms,
  ): OrderResult<OrderNegotiation>

  suspend fun respond(
      orderId: String,
      operationId: String,
      expectedRevision: Int,
      decision: OrderDecision,
  ): OrderResult<OrderNegotiation>
}
