# Live commando position foundation (#259, part 1)

AI assistance: OpenAI Codex (implementation, tests and documentation).

## Contract

`CommandoPositionRepository` replaces or deletes the authenticated owner's one position.
`observe(ownerId, orderId?)` is cold and cancellable: no collection scan. Exceptions indicate
failed authorization/transport; null means absent, unconfirmed, malformed or expired.
Only server-confirmed snapshots are exposed. A quiet snapshot expires after **120 seconds**,
including without another write. Capture time is a cross-device UTC Instant, never the
monotonic DeviceLocation timestamp. Coordinates and accuracy must be finite and valid;
server rules reject future, stale or regressing capture timestamps. Clock skew is an error,
not permission to label an old fix live. No location history is stored.

`commandoPositions/{ownerUid}` contains exactly `tripId`, `latitude`, `longitude`,
`accuracyMeters`, `capturedAt` and server-set `updatedAt`. The owned trip is a discovery
witness; discovery requires it to be PUBLISHED with a future departure. Only the owner
writes/deletes this document. No unauthenticated reads or collection listing are permitted.

For a closed trip, the requester supplies one active order ID. Their private document
`commandoPositions/{ownerUid}/viewers/{requesterUid}` contains only that `orderId`.
This is a lookup witness, not a durable access grant: EVERY position read checks the actual
order requester, active status (PENDING/ACCEPTED/READY_FOR_HANDOFF) and persisted trip owner.
A forged witness cannot grant access and a terminal order immediately fails authorization.
Tracking also observes the witness order itself: terminal/deleted/foreign-order snapshots close
the position flow even when no position changes. Cancellation or logout removes the position,
order (when tracking) and Auth listeners together. Server rules still independently restrict reads.
Position snapshots and order confirmation are combined independently. Pending/cached order
snapshots hide the position; active server confirmation restores the last confirmed position
without another position event, only if its original capture time is still fresh.
Pointers hold no positions and are readable/deletable only by their requester. The integration
must replace the pointer if another active order should take over; retaining a pointer does
not retain authorization. Discovery positions are readable by any authenticated user while
the witness trip is open; radius/filtering is a discovery UI rule, not a geographic read ACL.

## Integration boundary

This PR supplies storage/observation and emulator-validated rules. It does not activate sharing
in the app, collect device locations, add markers or implement discovery/tracking modes.
The following integration must reuse DeviceLocationProvider, require explicit foreground
opt-in, validate monotonic fix age, convert capture time correctly, and show sharing/errors.
Await stopSharing BEFORE sign-out; the delete must reach the server. An offline deletion
cannot guarantee immediate remote erasure, so readers also expire snapshots. Cancel flows
at lifecycle/session exit; Firebase auth changes additionally terminate active SDK listeners.
Publish cadence, GPS wiring, opt-in controls, active-order subscriptions and map integration
belong to the follow-up. Fake storage models transport/freshness, not backend authorization.
The fake takes an observable StateFlow identity: logout/account change closes a quiet stream
and releases its session/position subscriptions. Regressing timestamps are rejected inside the
atomic storage update, matching Firestore's write rule; equal capture times remain permitted.

## Validation

Run `./gradlew ktfmtCheck check assembleDebug` and
`bash scripts/ci/run-firebase-emulator-tests.sh`. Rules changes are local until deployed;
no production rule deployment or real-device location-sharing demonstration is claimed here.

## Reactive map state (#270)

Upcoming-trip and requester-order feeds expose server-confirmed query snapshots.
Unconfirmed orders never imply discovery. `OrderStatus.isActiveForTracking()` centralizes
active states; LiveMapViewModel groups by owner, retains all order links and expires fixes
without network polling. Collection cancellation and auth changes release query listeners.
Sharing controls and screen wiring are delivered separately in #271 and #272.

## Foreground sharing controls (#271)

Explicit opt-in and coarse permission precede location acquisition. Publish every 10 seconds
while foregrounded, preserving capture age. Backgrounding, lost eligibility and session exit
stop the publisher. Normal sign-out must await confirmed deletion; failed cleanup stays
visible and retryable. Rotation preserves foreground consent, background return does not.
These tested controls are connected to authenticated navigation in #272.
