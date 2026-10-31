# Orders persistence — #145

AI assistance: OpenAI Codex; #147 trip-owner query and #193 checklist: Claude Code. Shared-contract choices below remain subject to teammate review.

`orders/{id}` stores `tripId`, `requesterId`, `items` (productId/name/unitPriceCents/quantity),
`commissionCents`, `status` and `createdAt`. The document ID is the Order ID.
Money uses integer CHF cents; Kotlin and rules reject duplicate products, invalid quantities and Int
overflow. Orders contain 1–5 distinct products (MAX_ORDER_PRODUCTS), permitting exhaustive rules
validation without loops. Catalogue and Review explain oversized drafts and disable continuation or
submission until a product is removed; quantities do not count toward this distinct-product limit.
Creation derives the requester from the session, writes PENDING and a server timestamp, and confirms
the saved snapshot through a SERVER read. The trip must be PUBLISHED with a strictly future departure;
the rules enforce eligibility at write time, independently of the preliminary application lookup.
New orders on the authenticated requester's own trip are forbidden: the production adapter returns
InvalidData before writing, and rules also reject direct SDK writes. Existing stored orders and
read access remain unchanged; this creation guard performs no migration or decision transition.
The shared fake also rejects self-orders when a TripRepository is injected, checks the returned trip
ID and session after lookup, and never inserts on lookup failure. Without an injected lookup, creation
keeps standalone fixture behavior. This optional fake check does not emulate production trip timing
or publication validation; those remain enforced by the production adapter and rules.

Requester queries filter `requesterId == uid`, then sort by createdAt descending / ID ascending locally.
The automatic single-field index suffices; no composite Orders index is required by these queries.
Detail reads allow the requester or the associated trip's current owner. Deletes, and updates other
than owner decisions, remain denied.

`decideOrder(id, ACCEPT/REJECT)` atomically validates ownership and changes only PENDING status to
ACCEPTED/REJECTED. Identical finals return Success; opposite finals return AlreadyDecided(persistedOrder),
without writing. Rules denials reconcile only authorized final snapshots through a read-only transaction.
Decisions ignore trip status/time and deny legacy self-orders, which remain readable.

#147: `getOrdersForTrip(tripId)` checks the trip owner, then queries `tripId == id` and sorts locally
like requester queries. Rules allow that list only when the caller owns `trips/{tripId}`; an unfiltered
or foreign-trip query is denied. The automatic single-field index suffices.

All operations check session identity before/after suspension. Cancellation propagates. Missing documents
return NotFound when readable; a lookup denied by rules remains PermissionDenied (including an
unreadable/missing trip). Unavailable/network failures use Unknown. Retry uncertain decisions with the
same ID/intent, even from a fresh repository. Cancellation/session loss cannot undo committed writes;
their results are withheld from ended sessions. Server reads intentionally fail offline.

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

#193: `shoppingChecklists/{tripId}` stores only the trip owner's purchase progress: `purchased` maps a
product ID to the quantity it was checked for, plus a server `updatedAt`. Products and quantities are
recomputed from the trip's ACCEPTED orders (`buildShoppingChecklist`), so orders are never copied. An
item stays checked only while its checked quantity covers the current requirement. Each toggle is a
merge write of one product. Rules allow get/create/update only to the owner of `trips/{tripId}`,
exact fields, a map of at most 100 products and a server timestamp; list and delete are denied.
