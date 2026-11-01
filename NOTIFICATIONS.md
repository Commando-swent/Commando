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
Preferences are evaluated at first processing, not original event time; these semantics remain
proposals for teammate review.

Rules enforce recipient-only reads, exact preference types, canonical event identity, immutable
notification content and read-only-to-true updates. Results sort by event time descending then ID.
No composite index is required. Run ./gradlew check assembleDebug and the production-rules Firebase
security profile. Emulator validation does not establish deployed-backend configuration.

#201 is delivered through storage and atomic-event increments, with order regression tests. #202
supplies screens, ViewModels and navigation. The versioned rules enforce events for every order write.
Activate that requirement only after migrating clients: older APKs do not publish these events.

On 2026-11-01, command-o received an additive notification rollout. Its published rules preserve
the previous order permissions and add the recipient-only event, inbox and preference rules.
The updated APK writes its events atomically; older APKs remain usable but do not generate
notifications. Legacy creation and both decisions were checked alongside the notification/security
suite (44 tests) against this rollout policy. The stricter event-required policy remains a later,
coordinated deployment. Keep the published snapshot and ruleset reference when changing this policy.

NotificationsViewModel (#202) prepares independent, lazily loaded inbox/preference state. It uses
the shared SessionGuard, confirms saves before changing displayed values and defers reloads during
saves. A failed save keeps the last confirmed data and offers an explicit retry of the failed action.

Profile now opens both Notifications and Notification preferences. CommandoApp owns the routes,
uses an account-bound injectable notification repository, and refreshes each page on resume. Inbox
rows open the existing Order Details route with the full opaque ID, encoded once. Only the visible
reference is shortened (eight trailing characters); it never supplies the navigation argument.

The domain model remains independent of Android colors/text. The screens reuse OrderStatusBadge
and the Command'o Material palette, show explicit read/unread text, and format dates using the
current locale and time zone. EN/FR resources cover every state. Whole preference rows are switches;
loading/save errors expose a retry and confirmed values survive failed writes. Lists and settings
scroll on short displays. Figma proposal: file WPmz3r0efh7jF2BQmUTfRt, page 799:7200. The proposed
contract and design still need teammate review; local implementation does not imply that approval.
