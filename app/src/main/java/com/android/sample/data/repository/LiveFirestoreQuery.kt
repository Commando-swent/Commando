package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Session-bound server snapshots; null is unconfirmed, never a confirmed empty collection. */
internal fun Query.observeServerDocuments(
    firestore: FirebaseFirestore,
    uid: String,
): Flow<List<FirestoreDocument>?> = callbackFlow {
  val auth = FirebaseAuth.getInstance(firestore.app)
  val registration =
      addSnapshotListener({ it.run() }, MetadataChanges.INCLUDE) { snapshot, error ->
        when {
          auth.currentUser?.uid != uid ->
              close(
                  FirebaseFirestoreException(
                      "Session ended",
                      FirebaseFirestoreException.Code.UNAUTHENTICATED,
                  )
              )
          error != null -> close(error)
          snapshot == null ||
              snapshot.metadata.isFromCache ||
              snapshot.metadata.hasPendingWrites() -> trySend(null)
          else -> trySend(snapshot.documents.map { it.toFirestoreDocument() })
        }
      }
  val session = FirebaseAuth.AuthStateListener {
    if (it.currentUser?.uid != uid)
        close(
            FirebaseFirestoreException(
                "Session ended",
                FirebaseFirestoreException.Code.UNAUTHENTICATED,
            )
        )
  }
  auth.addAuthStateListener(session)
  awaitClose {
    registration.remove()
    auth.removeAuthStateListener(session)
  }
}
