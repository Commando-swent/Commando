# Live commando positions and map integration (#259)

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

## Foundation boundary (PR #265)

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

## Foreground integration (part 2)

The Commando Home card provides explicit coarse-location consent and a sharing switch.
No location is requested before opt-in. The publisher requests a fresh device fix every
10 seconds while foregrounded, preserves the fix's original monotonic age when converting
it to UTC, and keeps only one overwritten position. A future published trip is preferred;
an in-progress trip can continue sharing for existing orders. Missing eligible trips,
permission loss, failed acquisition/publication and failed deletion have localized feedback.
Backgrounding, switching to requester mode and normal sign-out stop the publisher.
Normal sign-out waits for a confirmed deletion; if that fails, the user stays signed in and
can retry stopping. A forced external logout cannot guarantee deletion; readers still expire
old fixes. Rotation retains the foreground opt-in; returning from background requires a new tap.

`OrderStatus.isActiveForTracking()` is the shared canonical mapping: PENDING, ACCEPTED and
READY_FOR_HANDOFF are active; REJECTED, CANCELLED and DELIVERED are terminal.
The visible map subscribes to server-confirmed trip and requester-order query snapshots,
then observes one position per commando. Loading or unconfirmed orders never become an
empty order list. Multiple orders for the same commando share one marker and retain all
order links. Active orders resolve their trips independently of discovery, including closed
trips. A missing/expired/off-map position keeps its tracking order summary available.
When the last order becomes terminal the screen automatically returns to discovery.

Discovery reuses the existing shared trip filters and the 500 m handoff radius. Its distance
origin is a usable device fix, otherwise the selected delivery point, otherwise the campus
center (46.521, 6.566). This fallback never changes the selected delivery filter. Only fresh
sharing commandos with future published filtered trips qualify. Existing trip handoff
markers remain available. Person markers use the same projection/camera as handoff markers.
A local one-second tick removes expired positions/trips without a new backend event;
it does not poll the network. Query/position subscriptions are cancelled on screen exit.

This remains the existing fixed campus map: no navigation SDK, directions, clustering,
background/screen-lock sharing, location history or offline/reconnection product flow.
The emulator tests validate production rules and cross-client live query updates; Android
instrumentation validates lifecycle/UI with injected fixes. They do not replace a deployed
backend demonstration with a requester and two physical commandos. Production rules must
be deployed before live use. Human review, Figma alignment and that demo remain delivery checks.
