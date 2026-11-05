# Notification storage and order events — #201 / #204

AI assistance: OpenAI Codex (implementation, tests and documentation, including c440b5b).

NotificationRepository uses authenticated session identity, SERVER reads and account-change guards.
Screens cannot supply recipients or publish events directly. #204 publishes events through order
writes; #202 adds state management. Screens/navigation are planned for S5. No push/background delivery.

orderEvents/{orderId}_{eventType} stores immutable orderId, recipientId, type and occurredAt.
Types: ORDER_PLACED/ORDER_ACCEPTED/ORDER_REJECTED. Valid future types are skipped; extra fields ignored.
Malformed required fields still fail. Order creation publishes ORDER_PLACED in the same batch;
Accept/Reject publishes its event in the same transaction. Rules require both writes together,
validate the persisted transition and recipient, and deny event updates/deletes. Repeated decisions
read the final state without publishing again. Recipient storage tests use admin-seeded events;
integration tests exercise real authenticated order writes.
The existing ACCEPTED -> READY_FOR_HANDOFF transition remains available without publishing another
notification event; its retries preserve the already-published decision event.

users/{uid}/notificationPreferences/settings stores newOrders/orderDecisions (both default true).
Saves specify only changed toggles; transactions preserve other-device updates and initialize defaults.
users/{uid}/notifications/{eventId} copies its event with visible/isRead. First processing reads current
preferences transactionally; muted records prevent replay, while existing entries retain visibility/read.
Applying preferences at processing time remains a contract proposal for teammate review.

Rules enforce recipient privacy, typed settings, canonical identity, immutable content and read-to-true.
Refresh reloads full recipient history and processes unseen events sequentially; reads/latency grow.
Results sort newest first, then ID (no composite index). Follow-up: pagination/incremental sync
preserving ordering/deduplication (proposal, not implemented).
Run ./gradlew check assembleDebug and Firebase emulator profiles; these do not verify deployed rules.

## Shared Firebase cutover for #206

The repository contains one strict policy, `firestore.rules`; there is no additive or transitional
policy. New builds publish order events and need these rules. Older builds omit events and their
order creation/decisions are denied after this policy is deployed. Mixed builds are not supported.

Yasmine Bennani, the PR author, publishes the rules from merged `main` immediately after #206 merges.
She coordinates the cutover with the team and Firebase project administrator. Confirm deployment
access and a team update window before merging; this is a merge prerequisite. Merging, CI and
emulator runs do not publish rules to the shared backend.

1. Before merging, pause shared-app order operations with the team, arrange the new APK update,
   and retain the previous release SHA, APK and deployed rules for rollback.
2. Immediately after #206 merges, the confirmed publisher checks out clean `main`, pulls the merged
   revision and verifies the Firebase target against the app configuration (`command-o`). Deploy
   only the checked-in rules: `firebase deploy --only firestore:rules --project command-o`.
3. Allow rule propagation, then use the matching new APK to create an order and exercise Accept
   and Reject. Verify the intended recipient, one canonical event per operation and retry safety.
   Emulator success alone does not establish that the deployed backend is ready.
4. Update the team's old APKs before resuming order operations. Record the merged SHA, publisher,
   deployed rules revision and smoke-test results in the PR.
5. If checks fail, keep order operations paused and restore the previous rules and matching APK
   together; reverting only one side leaves an incompatible client/policy pair.

No shared-backend deployment is performed by this PR's local preparation.
[Firebase deployment reference](https://firebase.google.com/docs/rules/manage-deploy).
