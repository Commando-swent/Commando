# Orders persistence — #145

AI assistance: OpenAI Codex. Shared-contract choices below remain subject to teammate review.

`orders/{id}` stores `tripId`, `requesterId`, `items` (productId/name/unitPriceCents/quantity),
`commissionCents`, `status` and `createdAt`. The document ID is the Order ID.
Money uses integer CHF cents; Kotlin and rules reject duplicate products, invalid quantities and Int
overflow. Orders contain 1–5 distinct products (MAX_ORDER_PRODUCTS), permitting exhaustive rules
validation without loops. Catalogue and Review explain oversized drafts and disable continuation or
submission until a product is removed; quantities do not count toward this distinct-product limit.
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

Rules enforce identity, eligible trip, exact order/item fields, nonblank product IDs/names, integer
prices/quantities, line and overall Int totals including commission, unique products, timestamp,
initial status and immutability. Snapshots are requester-supplied estimates, not certified catalogue prices.
Malformed stored snapshots fail decoding. The 5-product schema bound requires shared-contract review.

Within the session repository, an unchanged draft retries with the same document ID until confirmation.
A server lookup recovers an already-committed order before checking current trip eligibility or writing.
Failed writes/reads and cancellation retain that ID; successful confirmation releases it so a later
intentional identical purchase is distinct. Submissions are serialized and keyed by requester + snapshot;
an edited draft is a new submission. Pending IDs live in memory: process death/repository replacement
requires a future durable submission token if recovery across restarts is needed.

Run `./gradlew check assembleDebug` and `bash scripts/ci/run-firebase-emulator-tests.sh`.
The Orders security profile tests the real SDK/adapter with production rules and explicit SERVER reads.
Before production activation, the Firebase project owner must review and deploy rules/indexes to the
confirmed project, then record an Android persistence check. No production deployment is performed or
claimed by these changes. Local emulator success is not evidence of production configuration.
