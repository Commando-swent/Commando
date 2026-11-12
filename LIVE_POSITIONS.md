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
(PENDING/ACCEPTED/READY_FOR_HANDOFF) and trip owner on every position read. Terminal orders
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

## Foreground and selected-order integration (#292)

Sharing controls are hosted by the authenticated app, so navigating away from Home does not
stop foreground sharing. Backgrounding, role exit and root disposal start ViewModel-owned
shutdown; rotation retains consent. Normal sign-out awaits cleanup and failed deletion remains
retryable. Returning from background requires a new explicit opt-in.

The publisher checks owned trips each cycle and prioritizes IN_PROGRESS over future PUBLISHED.
It waits 10 seconds between acquisition/publication cycles; network and acquisition time add to
the interval. A fetch failure stops sharing until explicit retry, rather than treating cached or
unknown eligibility as authorization. Rules independently reject ineligible publication.

Order Details exposes tracking only to the selected active order's requester. The tracking
ViewModel resolves its persisted order/trip, then observes that owner with the order witness.
The map renders one distinct marker, auto-centers the first fix, and supports recentering,
pan/zoom, missing/out-of-campus feedback and explicit retry. Leaving/backgrounding clears
coordinates and cancels the observation. Permission is needed to publish, not to view a
commando's authorized shared position. Existing trip discovery remains unchanged.

The sharing components are #271 / #274; this PR wires them into the application.

The S6 work in #270/#272 (temporarily closed) retains automatic discovery, automatic mode
switching, multi-commando grouping and summaries. #273/#275 are closed; this implementation
does not depend on them. Deployed rules, actual physical acquisition, Figma alignment and a
production two-user demonstration remain checks to perform separately from injected tests.
