package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/** Server-only persistence boundary; transaction callbacks may be retried by Firestore. */
internal interface RatingFirestoreDataSource {
  suspend fun get(collection: String, id: String): FirestoreDocument?

  suspend fun <T> transaction(action: (RatingTransaction) -> T): T
}

/** Synchronous transaction reads must all precede writes. */
internal interface RatingTransaction {
  fun get(collection: String, id: String): FirestoreDocument?

  fun set(collection: String, id: String, data: Map<String, Any?>)
}

/** Adapts SDK snapshots without caching success or swallowing uncertain commit failures. */
internal class FirebaseRatingFirestoreDataSource(private val firestore: FirebaseFirestore) :
    RatingFirestoreDataSource {
  override suspend fun get(collection: String, id: String): FirestoreDocument? =
      firestore.collection(collection).document(id).get(Source.SERVER).await().let {
        if (it.exists()) it.toFirestoreDocument() else null
      }

  override suspend fun <T> transaction(action: (RatingTransaction) -> T): T =
      firestore
          .runTransaction { transaction ->
            action(
                object : RatingTransaction {
                  override fun get(collection: String, id: String): FirestoreDocument? =
                      transaction.get(firestore.collection(collection).document(id)).let {
                        if (it.exists()) it.toFirestoreDocument() else null
                      }

                  override fun set(collection: String, id: String, data: Map<String, Any?>) {
                    transaction.set(firestore.collection(collection).document(id), data)
                  }
                }
            )
          }
          .await()
}
