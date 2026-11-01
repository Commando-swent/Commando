# Notification storage and order events — #201

AI assistance: OpenAI Codex (implementation, tests and documentation).

NotificationRepository exposes the authenticated recipient's inbox, idempotent read action and
preferences. Its Firestore adapter uses SERVER reads and session checks around asynchronous work.
Screens use the interface; they cannot supply a recipient or create an event.

orderEvents/{orderId}_{eventType} is the immutable source (orderId, recipientId, type, occurredAt).
Supported types: ORDER_PLACED, ORDER_ACCEPTED and ORDER_REJECTED. Order creation publishes ORDER_PLACED in the same batch; a decision publishes its event in the same
transaction. Rules require both writes together, verify the persisted order transition and recipient,
and deny event updates/deletes. Repeated decisions read the final state without publishing again.
Recipient storage tests use admin fixtures; integration tests use real authenticated order writes.

users/{uid}/notificationPreferences/settings stores boolean newOrders and orderDecisions, both enabled
by default. users/{uid}/notifications/{eventId} copies its canonical event and stores visible/isRead.
First processing transactionally reads preferences and creates this record. Muted events keep an
invisible record: re-enabling a category never replays them. Existing entries remain visible/read.
Evaluating preferences at first processing rather than event time remains a proposal for teammate review.

Rules enforce recipient-only reads, exact preference types, canonical event identity, immutable
notification content and read-only-to-true updates. Results sort by event time descending then ID.
No composite index is required. Run ./gradlew check assembleDebug and the production-rules Firebase
security profile. Emulator validation does not establish deployed-backend configuration.

#201 is delivered through storage and atomic-event increments, with order regression tests. #202
supplies screens, ViewModels and navigation. Deploy these rules alongside the updated app: older
clients writing orders without their events will be rejected. No cloud deployment was performed.
