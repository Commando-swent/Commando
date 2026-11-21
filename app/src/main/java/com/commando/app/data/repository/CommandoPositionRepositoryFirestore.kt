package com.commando.app.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.isDocumentId
import com.commando.app.model.CommandoPosition
import com.commando.app.model.OrderStatus
import com.commando.app.model.isActiveForTracking
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

  override suspend fun publish(tripId: String, position: CommandoPosition) =
      write(tripId, position, false)

  override suspend fun publishForDiscovery(tripId: String, position: CommandoPosition) =
      write(tripId, position, true)

  private suspend fun write(tripId: String, position: CommandoPosition, discoverable: Boolean) {
    val uid = user()
    require(tripId.isDocumentId() && position.isFresh(clock.instant()))
    firestore
        .batch()
        .set(
            positions.document(uid),
            mapOf(
                "tripId" to tripId,
                "latitude" to position.latitude,
                "longitude" to position.longitude,
                "accuracyMeters" to position.accuracyMeters,
                "capturedAt" to Timestamp(position.capturedAt),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        )
        .set(
            consent(uid),
            mapOf(
                "enabled" to discoverable,
                "tripId" to tripId,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        )
        .commit()
        .await()
    checkSession(uid)
  }

  private fun consent(ownerId: String) =
      positions.document(ownerId).collection("discovery").document("consent")

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  override fun observeDiscovery(ownerId: String, tripId: String): Flow<CommandoPosition?> = flow {
    val uid = user()
    require(ownerId.isDocumentId() && tripId.isDocumentId())
    emitAll(
        documents(consent(ownerId), uid)
            .flatMapLatest { metadata ->
              if (metadata?.get("enabled") == true && metadata.get("tripId") == tripId)
                  documents(positions.document(ownerId), uid)
                      .map { it?.toCommandoPosition() }
                      .catch { error ->
                        if (
                            error is FirebaseFirestoreException &&
                                error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                        ) {
                          emit(null)
                        } else throw error
                      }
              else flowOf(null)
            }
            .freshPositions(clock)
    )
  }

  override suspend fun stopSharing() {
    val uid = user()
    firestore.batch().delete(consent(uid)).delete(positions.document(uid)).commit().await()
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
    val positionUpdates =
        documents(positions.document(ownerId), uid).map { it?.toCommandoPosition() }
    val visible =
        if (orderId == null) positionUpdates
        else
            positionUpdates.combine(orderConfirmation(orderId, uid)) { position, confirmed ->
              position.takeIf { confirmed }
            }
    // Recheck capture age on every confirmation; pending writes must not reset freshness.
    emitAll(visible.freshPositions(clock))
  }

  private fun documents(reference: DocumentReference, uid: String): Flow<DocumentSnapshot?> =
      callbackFlow {
        val listener =
            reference.addSnapshotListener(
                { it.run() },
                MetadataChanges.INCLUDE,
            ) { doc, error ->
              when {
                !sameSession(uid) -> close(SecurityException(POSITION_SESSION_ENDED))
                error != null -> close(error)
                doc == null || doc.metadata.isFromCache || doc.metadata.hasPendingWrites() ->
                    trySend(null)
                else -> trySend(doc)
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
          doc.get("requesterId") != uid ||
              OrderStatus.entries
                  .firstOrNull { it.name == doc.get("status") }
                  ?.isActiveForTracking() != true ->
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
  return data?.let(::decodeCommandoPosition)
}

private fun decodeCommandoPosition(data: Map<String, Any?>): CommandoPosition? {
  return CommandoPosition(
      (data["latitude"] as? Number)?.toDouble() ?: return null,
      (data["longitude"] as? Number)?.toDouble() ?: return null,
      (data["accuracyMeters"] as? Number)?.toDouble() ?: return null,
      (data["capturedAt"] as? Timestamp)?.toInstant() ?: return null,
  )
}
