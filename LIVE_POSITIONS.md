# Selected-order live positions (#259 / #269)

AI assistance: OpenAI Codex (implementation, tests and documentation).

## Contract

`CommandoPositionRepository` replaces/deletes the authenticated owner's one position.
The document contains `tripId`, latitude, longitude, accuracy, UTC capture time and a server
update timestamp. No location history is stored. Publication requires an owned IN_PROGRESS
trip or a PUBLISHED trip with a future departure. Invalid, future, stale and regressing
capture timestamps are rejected; equal capture timestamps are allowed.

`observe(ownerId, orderId?)` is cold and cancellable. A requester supplies an active order ID;
only the owner can read without an order witness. Authenticated strangers cannot read a
position even while the trip is published, and collection scans are denied. Discovery is
outside the current sprint scope.

The private `commandoPositions/{ownerUid}/viewers/{requesterUid}` pointer stores one order ID.
It is a lookup witness, not an ACL: rules recheck the persisted requester, active status
(PENDING/ACCEPTED/READY_FOR_HANDOFF), trip owner and exact equality between the order's
trip ID and the published position's trip ID on every position read. An order on another
trip of the same commando grants no access. Missing positions are readable only by the owner
or an authenticated requester with a valid active-order witness, preventing an existence oracle.
Terminal orders
revoke access. The repository also watches the order, so a quiet stream closes on revocation.
Pointers contain no coordinates and are readable/deletable only by their requester.

Only server-confirmed positions are exposed. Null means absent/unconfirmed/malformed/expired;
exceptions mean authorization or transport failure. Quiet snapshots expire after 120 seconds.
Order confirmation and position updates are combined independently: confirming a pending order
restores the previous fix only if its original capture time is still fresh. Cancellation or
logout removes position, order and Auth listeners. Fake storage models transport and freshness,
not authorization; its observable identity terminates quiet streams on logout/account change.

## Integration and verification

This backend does not activate GPS collection or render markers. Integration must reuse the
existing provider/map, require explicit foreground opt-in, preserve the original monotonic fix
age when converting to UTC, cancel late results and display sharing/cleanup failures.
Await deletion before normal sign-out while still authenticated. Offline deletion or forced
external logout cannot guarantee immediate remote erasure; reader expiry is the fallback.

Run `./gradlew ktfmtCheck check assembleDebug` and the Firebase emulator security suite.
Versioned rules are not deployed automatically. Emulator/injected coordinates do not establish
production-rule deployment or physical-device location acquisition.

## Foreground sharing controls (#271 / #274)

The reusable controls require explicit permission/consent, retain consent on rotation and
invalidate late permission results after background/role exit. Shutdown belongs to the
ViewModel, so disposing the host starts cleanup without a composition-bound coroutine.
The publisher prioritizes IN_PROGRESS trips and rechecks eligibility each cycle; failures
stop publication until explicit retry. The interval is 10 seconds plus acquisition/network time.
Normal sign-out callers must await deletion and keep failures retryable.

This PR supplies the publisher, permission/lifecycle host and Home content slots. It does
not wire them into CommandoApp or implement requester tracking. That integration is #292.
The screenshot shows these controls in the integration harness; app wiring is a separate PR.
