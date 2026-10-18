# Orders persistence — #145

AI assistance: OpenAI Codex. Shared-contract choices below remain subject to teammate review.

`orders/{id}` stores `tripId`, `requesterId`, `items` (productId/name/unitPriceCents/quantity),
`commissionCents`, `status` and `createdAt`. The document ID is the Order ID.
Money uses integer CHF cents; validation rejects duplicate products, invalid quantities and Int overflow.
Creation derives the requester from the session, writes PENDING and a server timestamp, and confirms
the saved snapshot through a SERVER read. The trip must be PUBLISHED with a strictly future departure;
the rules enforce eligibility at write time, independently of the preliminary application lookup.

Requester queries filter `requesterId == uid`, then sort by createdAt descending / ID ascending locally.
The automatic single-field index suffices; no composite Orders index is required by these queries.
Detail reads allow the requester or the associated trip's current owner. Baseline trip-wide lists,
updates and deletes are denied. #147 owns the future trip query; #148 owns atomic decisions and statuses.
No placeholder methods or decision writes are introduced here.

All operations check session identity before/after suspension. Cancellation propagates. Missing documents
return NotFound when readable; a lookup denied by rules remains PermissionDenied (including an
unreadable/missing trip). Unavailable/network failures use the existing Unknown result. No errors or
statuses were added to the shared API. After an account change a committed write can remain stored,
but its result is not delivered to the previous session. Server reads intentionally fail offline.

Rules enforce identity, eligible trip, exact top-level fields, commission range, timestamp, initial
status and immutability. They require a non-empty items list; per-item types, uniqueness and arithmetic
are validated by the adapter/fake, not universally by rules for arbitrary lists. These snapshots are
requester-supplied estimates, not server-certified catalogue prices. Malformed stored snapshots fail
decoding. A stricter server-side item schema needs a separately agreed representation/bound.

Run `./gradlew check assembleDebug` and `bash scripts/ci/run-firebase-emulator-tests.sh`.
The Orders security profile tests the real SDK/adapter with production rules and explicit SERVER reads.
Before production activation, the Firebase project owner must review and deploy rules/indexes to the
confirmed project, then record an Android persistence check. No production deployment is performed or
claimed by these changes. Local emulator success is not evidence of production configuration.
