# Notification storage and order events — #201 / #204

AI assistance: OpenAI Codex (implementation, tests and documentation).

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
Shared Firebase uses an additive rollout. Activate the versioned event-required rules only after
client migration; emulator runs do not deploy the policy to the shared project.
