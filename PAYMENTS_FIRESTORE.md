# Simulated payment and refund persistence

This is the proposed shared contract for #385 / #400. Agree it with the checkout
owner before merging or enabling a paid checkout flow. No real payment provider
is called, and the transaction callback must remain pure because Firebase retries it.

## Stored documents

- `payments/{orderId}`: `orderId`, `requesterId`, integer `amountCents`, `method`
  (`TWINT` or `CARD`) and `status` (`PENDING`, `CONFIRMED`, `FAILED`, `UNCERTAIN`).
  The amount includes the persisted products and commission. Checkout owns capture.
- `refunds/{orderId}`: `operationId` (`refund-{orderId}`), `orderId`, `paymentId`
  (the order ID), `requesterId`, `requestedBy`, integer `amountCents`, `status`
  (the same four outcomes) and server `updatedAt`.

The requester and trip owner can get these documents; collection listing is denied.
Current rules deliberately deny client payment writes until #385 defines capture.
Emulator tests seed captures with the emulator administrator, never with open rules.

## Cancellation and retry

Only a PENDING requester or the trip owner of an ACCEPTED order may initiate its
refund. The transaction reads the order, trip, captured payment and previous refund.
The amount and operation ID never come from UI input. Nonterminal retries reuse
the operation; confirmed retries return it without writing or processing it again.

An existing refund reserves the order against forward transitions. Paid pending
orders cannot be edited, and cancellation requires a matching confirmed refund.
PENDING, FAILED and UNCERTAIN refunds never authorize terminal cancellation.
Unpaid orders retain their existing lifecycle; payment PENDING/UNCERTAIN fails closed.
The existing order repository owns the final cancellation and its canonical event;
owner cancellation integration also depends on #403 / #404. Refunds emit no events.

Deploy the coordinated production rules before enabling #407. Validate with
`./gradlew check assembleDebug assembleDebugAndroidTest` and
`bash scripts/ci/run-firebase-emulator-tests.sh`. Emulator success does not establish
deployed-backend or real-provider behavior. Bind repository identity permanently to
one session and discard late results when that session ends.
