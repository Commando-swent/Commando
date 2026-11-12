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
Existing lifecycle rules permit closed/completed trip reads; historical CANCELLED trips remain owner-only.

Run `bash scripts/ci/run-firebase-emulator-tests.sh` for production-rule security and repository integration tests,
plus `./gradlew check` for ordinary unit/static checks. Ordinary check excludes emulator profiles.
Tests use Firebase anonymous-auth accounts as authenticated actors; signed-out clients have no authentication.
Rules are not deployed by tests. Deployment and CI/SonarCloud review remain separate steps.
