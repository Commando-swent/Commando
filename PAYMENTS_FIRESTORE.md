# Simulated payment and refund persistence

The shared repository processes simulated payments and refunds without accepting
amounts, identities or credentials from the caller. No real money is transferred.

## Documents

- `payments/{orderId}`: `orderId`, `requesterId`, integer `amountCents`, `method`
  (`TWINT`/`CARD`), `status` (`PENDING`/`CONFIRMED`/`FAILED`/`UNCERTAIN`).
  Captured products plus commission stay immutable; the payment ID is the order ID.
- `refunds/{orderId}`: `operationId` (`refund-{orderId}`), `orderId`, `paymentId`
  (order ID), `requesterId`, `requestedBy`, integer `amountCents`, `status` and
  server `updatedAt`. Refunds use the same four outcomes.

Participants can get these documents; listing/deletion is denied. Only the
authenticated requester can create a payment for an ACCEPTED order. The transaction
derives its amount from the persisted items and commission. Confirmed, pending and
uncertain payments are read-only; a failed payment may retry the same order-based ID
and change its simulated method.

## Lifecycle and rollout

Only a legacy pending requester or an accepted-order owner can initiate a refund.
Payment and refund transactions derive identity/amount from order, trip, payment and
refund reads. Retries keep the operation; confirmed refunds are read-only. PENDING,
FAILED and UNCERTAIN refunds cannot authorize terminal cancellation. Rules use saved
refunds to block forward transitions; captured pending orders cannot be edited or
rejected.
A recorded payment must be CONFIRMED before READY_FOR_HANDOFF. Legacy orders with
no payment remain compatible; globally requiring a payment belongs to #420.
The existing order repository owns cancellation and its event (#403/#404).
Client CONFIRMED writes are simulation-only. Before #407 or a real provider,
confirmation must move to a trusted server and client confirmation writes be denied.

Coordinate/deploy production rules before #407. Simulation callbacks must remain
pure because transactions retry; no real provider is called. Emulator validation
does not establish deployed-backend behavior. Bind identity to one permanent session.

## Offline contract (#426 / #407)

All payment/refund operations require server transactions: cached records cannot
satisfy offline reads, and refunds are not queued. A transport failure returns
`PaymentError.UNKNOWN`, never a missing record or confirmed refund. Keep the order
unchanged, show retry feedback, and reconcile persisted state after reconnection
before retrying the same operation; a lost response may follow a server commit.
