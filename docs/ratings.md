# Firestore ratings (#261, parent #234)

AI assistance: OpenAI Codex contributed implementation, tests and documentation; teammate review is required.

The stack starts with internal transport, decoding and relationship-validation helpers and their unit tests.
That foundation does not wire a production rating repository or grant rating writes. The following backend
stage adds atomic submission, reconciliation, summaries and production security rules together.

`ratings/{orderId}` stores exactly `orderId`, `tripId`, `requesterId`, `commandoId`, integer `score`
(1–5), nullable `comment` (nonblank, at most 250 UTF-16 code units), and server `createdAt`.
Blank input comments normalize to null. Identities come from the persisted order and trip owner.
Only the authenticated requester may create feedback for their DELIVERED order; self-rating is denied.
Only persisted order participants may get feedback, including its absence; listing, updates and deletes are denied.

`ratingSummaries/{commandoId}` contains `ratingCount`, integral `scoreTotal`, and `lastOrderId`.
Any authenticated user may get a summary, including a missing one; listing and deletes are denied.
The repository returns count zero / null average when absent; otherwise average is total/count without rounding.
No additional indexes are required: all operations use document IDs, never collection queries.

One transaction reads relationships, feedback and summary before creating feedback and incrementing the summary.
Rules require the matching post-write summary AND a previously absent rating witnessed by `lastOrderId`.
Initial counters must be 1/score; later counters must be previous+1/previous+score. Neither side can commit alone.
The witness must match the newly created rating, preventing replay and multiple ratings sharing one increment.
Concurrent transactions retry against the latest state; an existing rating returns AlreadyRated without rewriting it.
If a submission transaction is denied, one read-only reconciliation rechecks the original session, delivered-order
eligibility and persisted identities. Only valid matching feedback yields AlreadyRated; absent/invalid feedback or
read failures preserve the original permission failure. Cancellation propagates. Reconciliation never retries writes.

Server-only reads confirm success. Network/confirmation failures return Unknown; use getRatingForOrder to reconcile
before retrying. Cancellation or an ended session cannot undo a committed transaction. Inject a session-invalidated
identity callback; UID comparison alone cannot detect switching away and back. No offline submission queue is provided.
Summaries serialize writes per Commando (contention at high volume) and expose the most recent order ID, but no
requester UID or comment. Admin SDK writes bypass rules; external imports must preserve both documents' invariants.
Existing lifecycle rules permit closed, completed and cancelled (#449) trip reads by id.

Run `bash scripts/ci/run-firebase-emulator-tests.sh` for production-rule security and repository integration tests,
plus `./gradlew check` for ordinary unit/static checks. Ordinary check excludes emulator profiles.
Tests use Firebase anonymous-auth accounts as authenticated actors; signed-out clients have no authentication.
Rules are not deployed by tests. Deployment and CI/SonarCloud review remain separate steps.

## Receipt rating dialog (#262)

Order Details offers the dialog once after an eligible requester's successful receipt confirmation.
Dismissal creates no rating; neither dismissal nor submission failure reverses delivery.
Unknown failures preserve input for retry, reading existing feedback before another write; other errors offer Close.
Success and AlreadyRated close the dialog and prevent another submission in that flow.
Session changes clear dialog state and invalidate its repository identity; late results are discarded.
Historical rating loading and display belong to #328, not this receipt-triggered flow.

## Trusted reliability tracking (#387)

AI assistance: OpenAI Codex contributed this persistence implementation and documentation.

`reliabilitySummaries/{ownerId}` contains exactly integer `completedCount` and
`lateCancellationCount`, each nonnegative and at most 2147483647, matching the star count's bound.
Authenticated users may get one summary, including its absence; listing and deletion are denied.
`reliabilitySummaryWrites/{ownerId}` contains exactly `lastOrderId`. Clients cannot read or delete
this witness. Private orders/events keep their existing read restrictions. Customer-star documents,
submission, averages and names are unchanged. Tracking persists counts; calculation and UI belong to #448.

The agreed reliability contract is:

- Evaluated commitments = `completedCount + lateCancellationCount`.
- Reliability percentage = `completedCount / (completedCount + lateCancellationCount) × 100`.
- The evaluation window is cumulative eligible outcomes since reliability tracking was introduced.
- Minimum history is five evaluated commitments. Below five, including zero history, display no
  reliability percentage; #448 implements this calculation and presentation policy.
- Early cancellations and non-terminal orders are excluded. Orders remaining indefinitely in
  `READY_FOR_HANDOFF` contribute to neither counter; readiness alone is not completion.
- No historical backfill.

For #448, display the nearest whole percentage, rounding exact halves upward. Compute
`(completedCount.toLong() * 100 + evaluatedCount / 2) / evaluatedCount` only at five or more
evaluated commitments; the Int-bounded counters make the Long arithmetic overflow-safe.
A successful server read of an absent summary means zero tracked commitments, not proof of a
newly registered Commando. Zero and insufficient history show a count without a percentage.
Malformed documents and failed reads mean unavailable reliability: never fabricate a zero count
or percentage. Reliability loads and retries independently of customer stars; reads remain
server-only, with no offline fallback. AI assistance: OpenAI Codex contributed the #448 UI.

Receipt confirmation atomically increments completedCount with READY_FOR_HANDOFF -> DELIVERED.
An eligible ACCEPTED -> CANCELLED owner transition increments lateCancellationCount only when the
server cancellation time is at least ten minutes after the immutable ORDER_ACCEPTED event time.
Strictly under ten minutes is early; exactly ten minutes is late. Rules mirror
`OrderCancellation.kt`'s policy because Kotlin helpers cannot execute in Firestore rules.
Neither device time nor a caller-supplied classification is trusted. Existing payment/refund rules
remain mandatory: captured payment requires a matching CONFIRMED refund before terminal cancellation.

The first cancellation attempt proposes no increment. A permission denial is not evidence of lateness:
an authorized, session-checked read-back first reconciles a competing terminal transition. Only if
the order is still ACCEPTED does one alternative propose the late increment. Rules independently
revalidate timing, actor, refund, transition and counters. A second denial receives the same read-back;
unchanged state preserves the original denial. Other failures propagate; no offline writes are queued.
Every transaction callback and returned result checks the session. All transaction reads precede writes.

Counters use atomic increment transforms, not client-computed totals. Both the terminal transition
and counter/witness rules require an exact matching increment. The witness binds one order per owner
per atomic request; two commitments for one owner cannot share an increment. A terminal order cannot
transition again, so retries do not recount it. Counter and witness writes cannot commit alone, nor can
a counted terminal transition omit them. Concurrent operations serialize through the summary/witness.

Missing historical acceptance evidence remains penalty-free. This is not a new-order escape hatch:
PENDING -> ACCEPTED already requires the matching server-timestamped event, and clients cannot delete
or modify that event or rewrite an order's identity/status history. Malformed/future evidence fails
closed. Previously terminal orders are not backfilled. No completion timestamp is needed for this
agreed cumulative window; repairing legacy history is separate work.
Delivery tracking reads the persisted trip owner; supported trip lifecycle states permit that read.
Cancelled trips are readable by id (#449), and a trip cannot be cancelled while an order is open.
Admin SDK imports bypass rules and must preserve
order/event/counter invariants; missing evidence from an import cannot be distinguished from legacy data.

### Rule access budget and deployment

Static audit of the complete adapter transaction, counting distinct document states/paths and reused
rule lookups (before and after states counted separately):

| Operation | Maximum per write | Complete transaction |
| --- | ---: | ---: |
| Delivered (order, summary, witness) | 6 | 7 |
| Early cancellation (order, event) | 5 | 7 |
| Late cancellation (order, event, summary, witness) | 8 | 10 |

The late path includes payment/refund checks: trip, payment-after, refund-after, acceptance event,
cancellation-event-after, order-before/after, summary-before/after and witness-after. Counter/witness
validators use at most seven of those states; adapter reads add no new rule lookup path.
[Firestore limits](https://firebase.google.com/docs/firestore/security/rules-conditions#access_call_limits)
are ten access calls per operation and twenty per atomic request; cached accesses do not count.
The table is a static cached-path analysis rather than measured per-operation access counts.
Do not combine multiple owners' transitions in one request without reassessing the twenty-call budget.

Deploy the updated client and rules in coordination. Old clients omit reliability writes and will
receive permission errors for delivery and late cancellation after enforcement; early/requester
cancellation and rating submission retain their rules. New clients cannot write reliability under
old rules. Do not relax either side's atomic requirement for compatibility: require an upgraded client
before enabling enforcement. Record the actual enforcement rollout externally; no date is invented here.

### Coverage limitations

Exact ten-minute Firestore rule boundary coverage and controlled boundary-crossing retry coverage
remain gaps. Model-level boundary tests do not establish those server-side cases.
