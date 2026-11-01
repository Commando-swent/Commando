# Notification storage — #201, first increment

AI assistance: OpenAI Codex (implementation, tests and documentation).

NotificationRepository exposes the authenticated recipient's inbox, idempotent read action and
preferences. Its Firestore adapter uses SERVER reads and session checks around asynchronous work.
Screens use the interface; they cannot supply a recipient or create an event.

orderEvents/{orderId}_{eventType} is the immutable source (orderId, recipientId, type, occurredAt).
Supported types: ORDER_PLACED, ORDER_ACCEPTED and ORDER_REJECTED. Client publication is denied in
this increment; emulator tests seed canonical events through the admin fixture API. Validated atomic
publication alongside order writes comes in the next PR.

users/{uid}/notificationPreferences/settings stores boolean newOrders and orderDecisions, both enabled
by default. users/{uid}/notifications/{eventId} copies its canonical event and stores visible/isRead.
First processing transactionally reads preferences and creates this record. Muted events keep an
invisible record: re-enabling a category never replays them. Existing entries remain visible/read.
Preferences are evaluated at first processing, not original event time; these semantics remain
proposals for teammate review.

Rules enforce recipient-only reads, exact preference types, canonical event identity, immutable
notification content and read-only-to-true updates. Results sort by event time descending then ID.
No composite index is required. Run ./gradlew check assembleDebug and the production-rules Firebase
security profile. Emulator validation does not establish deployed-backend configuration.

#201 stays open until event publication and its order regressions land. #202 supplies the Figma
screens, ViewModels and navigation. This increment changes no order behavior and adds no UI/push.

NotificationsViewModel (#202) prepares independent, lazily loaded inbox/preference state. It uses
the shared SessionGuard, confirms saves before changing displayed values and defers reloads during
saves. A failed save keeps the last confirmed data and a retryable error. Its screen/navigation
bindings remain pending the approved Figma frames; no route or user-visible screen is added yet.
