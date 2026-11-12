package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.CommandoPosition
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import java.time.Clock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await

/** Server-confirmed positions. Firestore rules independently validate every eligibility witness. */
class CommandoPositionRepositoryFirestore(
    private val currentUserId: () -> String?,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val clock: Clock = Clock.systemUTC(),
) : CommandoPositionRepository {
  private val positions
    get() = firestore.collection("commandoPositions")

  private val auth
    get() = FirebaseAuth.getInstance(firestore.app)

  override suspend fun publish(tripId: String, position: CommandoPosition) {
    val uid = user()
    require(tripId.isDocumentId() && position.isFresh(clock.instant()))
    positions
        .document(uid)
        .set(
            mapOf(
                "tripId" to tripId,
                "latitude" to position.latitude,
                "longitude" to position.longitude,
                "accuracyMeters" to position.accuracyMeters,
                "capturedAt" to Timestamp(position.capturedAt),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        )
        .await()
    checkSession(uid)
  }

  override suspend fun stopSharing() {
    val uid = user()
    positions.document(uid).delete().await()
    checkSession(uid)
  }

  override fun observe(ownerId: String, orderId: String?): Flow<CommandoPosition?> = flow {
    val uid = user()
    require(ownerId.isDocumentId() && (orderId == null || orderId.isDocumentId()))
    if (orderId != null) {
      // Rules cannot search orders. A private pointer supplies a witness, not a trusted ACL:
      // each position read rechecks that order's current requester, status and trip owner.
      positions
          .document(ownerId)
          .collection("viewers")
          .document(uid)
          .set(mapOf("orderId" to orderId))
          .await()
      checkSession(uid)
    }
    val positionUpdates = snapshots(ownerId, uid)
    val visible =
        if (orderId == null) positionUpdates
        else
            positionUpdates.combine(orderConfirmation(orderId, uid)) { position, confirmed ->
              position.takeIf { confirmed }
            }
    // Recheck capture age on every confirmation; pending writes must not reset freshness.
    emitAll(visible.freshPositions(clock))
  }

  private fun snapshots(ownerId: String, uid: String): Flow<CommandoPosition?> = callbackFlow {
    val listener =
        positions.document(ownerId).addSnapshotListener(
            { it.run() },
            MetadataChanges.INCLUDE,
        ) { doc, error ->
          when {
            !sameSession(uid) -> close(SecurityException(POSITION_SESSION_ENDED))
            error != null -> close(error)
            doc == null || doc.metadata.isFromCache || doc.metadata.hasPendingWrites() ->
                trySend(null)
            else -> trySend(doc.toCommandoPosition())
          }
        }
    val sessionListener = FirebaseAuth.AuthStateListener {
      if (!sameSession(uid)) close(SecurityException(POSITION_SESSION_ENDED))
    }
    auth.addAuthStateListener(sessionListener)
    awaitClose {
      listener.remove()
      auth.removeAuthStateListener(sessionListener)
    }
  }

  private fun orderConfirmation(orderId: String, uid: String): Flow<Boolean> = callbackFlow {
    val listener = watchOrder(orderId, uid, { trySend(it) }, { close(it) })
    awaitClose { listener.remove() }
  }

  // Observe the authorization dependency as well as the position: revocation must end a quiet
  // stream.
  private fun watchOrder(
      orderId: String,
      uid: String,
      confirmed: (Boolean) -> Unit,
      revoked: (Throwable) -> Unit,
  ): ListenerRegistration =
      firestore.collection("orders").document(orderId).addSnapshotListener(
          { it.run() },
          MetadataChanges.INCLUDE,
      ) { doc, error ->
        when {
          !sameSession(uid) -> revoked(SecurityException(POSITION_SESSION_ENDED))
          error != null -> revoked(error)
          doc == null || doc.metadata.isFromCache || doc.metadata.hasPendingWrites() ->
              confirmed(false)
          doc.get("requesterId") != uid || doc.get("status") !in ACTIVE_ORDER_STATUSES ->
              revoked(
                  FirebaseFirestoreException(
                      "Position order is no longer active",
                      FirebaseFirestoreException.Code.PERMISSION_DENIED,
                  )
              )
          else -> confirmed(true)
        }
      }

  private companion object {
    const val POSITION_SESSION_ENDED = "Position session ended"
    val ACTIVE_ORDER_STATUSES = setOf("PENDING", "ACCEPTED", "READY_FOR_HANDOFF")
  }

  private suspend fun user(): String {
    currentCoroutineContext().ensureActive()
    return currentUserId()?.takeIf { it.isDocumentId() && sameSession(it) }
        ?: throw SecurityException("Position requires an authenticated session")
  }

  private fun sameSession(uid: String) = currentUserId() == uid && auth.currentUser?.uid == uid

  private fun checkSession(uid: String) {
    check(sameSession(uid)) { POSITION_SESSION_ENDED }
  }
}

/** Decodes untrusted snapshots; missing or wrongly typed fields must not become live markers. */
internal fun DocumentSnapshot.toCommandoPosition(): CommandoPosition? {
  val data = data?.toMap() ?: return null
  return CommandoPosition(
      (data["latitude"] as? Number)?.toDouble() ?: return null,
      (data["longitude"] as? Number)?.toDouble() ?: return null,
      (data["accuracyMeters"] as? Number)?.toDouble() ?: return null,
      (data["capturedAt"] as? Timestamp)?.toInstant() ?: return null,
  )
}
