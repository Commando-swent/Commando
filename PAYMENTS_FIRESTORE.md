# Simulated payment and refund persistence

Shared models and document paths for #385 / #400 were confirmed with the checkout
owner. Checkout owns `processPayment(orderId, method)` after ACCEPTED and must
reconcile UNCERTAIN from persistence before retrying the same operation.

## Documents

- `payments/{orderId}`: `orderId`, `requesterId`, integer `amountCents`, `method`
  (`TWINT`/`CARD`), `status` (`PENDING`/`CONFIRMED`/`FAILED`/`UNCERTAIN`).
  Captured products plus commission stay immutable; the payment ID is the order ID.
- `refunds/{orderId}`: `operationId` (`refund-{orderId}`), `orderId`, `paymentId`
  (order ID), `requesterId`, `requestedBy`, integer `amountCents`, `status` and
  server `updatedAt`. Refunds use the same four outcomes.

Participants can get these documents; listing/deletion is denied. Client capture
writes remain denied until #385 defines them; emulator captures are admin seeds.

## Lifecycle and rollout

Only a legacy pending requester or an accepted-order owner can initiate a refund.
The transaction derives identity/amount from order, trip, payment and refund reads.
Retries keep the operation; confirmed refunds are read-only. PENDING, FAILED and
UNCERTAIN refunds cannot authorize terminal cancellation. Rules use saved refunds
to block forward transitions; captured pending orders cannot be edited.
A recorded payment must be CONFIRMED before READY_FOR_HANDOFF. Legacy orders with
no payment remain compatible; deploy mandatory payment presence together with #385.
The existing order repository owns cancellation and its event (#403/#404).

Coordinate/deploy production rules before #407. Simulation callbacks must remain
pure because transactions retry; no real provider is called. Emulator validation
does not establish deployed-backend behavior. Bind identity to one permanent session.
