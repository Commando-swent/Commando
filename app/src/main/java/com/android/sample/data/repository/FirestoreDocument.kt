package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.google.firebase.firestore.DocumentSnapshot

/**
 * SDK-independent transport shared by Product, Trip and Order data sources.
 *
 * This is raw stored data, not a validated domain object. Each repository keeps its own decoder,
 * query policy and typed errors. The map is not a deep copy; consumers must not mutate its values.
 *
 * @property id Firestore document ID, not a path or a product's stored global business ID.
 * @property data Stored fields, including SDK values such as Timestamp and malformed values that
 *   the domain decoder must validate. Missing fields are not filled with domain defaults.
 */
internal data class FirestoreDocument(val id: String, val data: Map<String, Any?>)

/**
 * Materializes this snapshot without changing its read source, existence or authorization policy.
 *
 * Callers must check [DocumentSnapshot.exists] when a missing document means NotFound. Query
 * snapshots already contain existing documents. A null data map becomes empty, so domain decoders
 * retain responsibility for rejecting missing required fields.
 */
internal fun DocumentSnapshot.toFirestoreDocument(): FirestoreDocument =
    FirestoreDocument(id, data.orEmpty())
