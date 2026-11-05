# Shared Firestore helpers

AI assistance: OpenAI Codex. Follow-up to Lina's #209 and the internal Firestore KDoc review.

`FirestoreFailure.kt` classifies SDK/IO failures once. Product, Trip and Order retain typed domain
errors: Product/Trip map unauthenticated calls to PermissionDenied, while Order returns
NotAuthenticated. Order has no network error variant, so network failures remain Unknown, including
for Notification and Checklist, which already use `toOrderError()`. Wrapped causes stay Unknown.

`FirestoreDocument` holds only a storage ID and raw fields. ProductFirestoreDocument,
TripFirestoreDocument and OrderFirestoreDocument are internal type aliases of that single transport,
not three runtime implementations. Their existing names keep data-source signatures recognizable
and reduce integration changes in the open order branches. Product's former `documentId` property is
now `id`; this remains its storage/pagination ID, distinct from the global product ID in its fields.

`DocumentSnapshot.toFirestoreDocument()` replaces repeated SDK-to-transport conversion. It neither
reads Firestore nor decides whether a document exists: detail-read callers still check existence,
and domain decoders still validate fields. Timestamp values and malformed raw data are preserved.
The map is not a deep copy and must not be mutated by consumers.

Queries, pagination and read sources are unchanged: Orders/Product use server reads; Trip retains
its existing SDK-default source. Domain schemas, validation, security rules and atomic notification
writes remain in their existing owners. No universal CRUD repository or domain-result unification is
introduced. A generic operation wrapper cannot replace Order's session guard without preserving its
identity recheck after suspension/error and its coroutine cancellation semantics; that guard stays.

Validation: run `./gradlew check assembleDebug` and the Firebase adapter/security emulator profiles.
Unit regressions cover every Firestore error code, IO/wrapped failures, and raw snapshot conversion;
existing repository and emulator tests exercise schema decoding and query behavior.
