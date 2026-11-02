# Notification storage — #201

AI assistance: OpenAI Codex (implementation, tests and documentation).

NotificationRepository uses authenticated session identity, SERVER reads and account-change guards.
Screens cannot supply recipients or publish events. #204 adds publication, #202 state management;
screens/navigation are planned for S5. No push/background delivery.

orderEvents/{orderId}_{eventType} stores immutable orderId, recipientId, type and occurredAt.
Types: ORDER_PLACED/ORDER_ACCEPTED/ORDER_REJECTED. Valid future types are skipped; extra fields ignored.
Malformed required fields still fail. Publication is denied here; tests use admin-seeded events.

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
