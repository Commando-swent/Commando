package com.android.sample.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await

/** Stores explicitly published names in Firestore using a session-bound [currentUserId]. */
class PublicProfileRepositoryFirestore(
    private val currentUserId: () -> String?,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : PublicProfileRepository {
  private fun document(uid: String) = firestore.collection("publicProfiles").document(uid)

  override suspend fun getDisplayName(ownerId: String): String? = inSession {
    require(ownerId.isDocumentId()) { "Invalid public profile owner ID" }
    val profile = document(ownerId)
    // A transaction reads committed data even when a pending local delete hides the profile.
    val snapshot = firestore.runTransaction { transaction -> transaction.get(profile) }.await()
    if (snapshot.exists()) checkNotNull(snapshot.getString(DISPLAY_NAME)) else null
  }

  override suspend fun publishDisplayName(displayName: String) {
    inSession { uid ->
      require(isValidPublicName(displayName)) { "Invalid public display name" }
      document(uid).set(mapOf(DISPLAY_NAME to displayName)).await()
    }
  }

  override suspend fun removeDisplayName() {
    inSession { uid -> document(uid).delete().await() }
  }

  private suspend fun <T> inSession(operation: suspend (String) -> T): T {
    val context = currentCoroutineContext()
    context.ensureActive()
    val uid = checkNotNull(currentUserId()) { "Public profiles require an authenticated session" }
    check(uid.isNotBlank()) { "Public profiles require a non-blank authenticated UID" }

    fun checkSession() {
      context.ensureActive()
      check(currentUserId() == uid) { "Public profile session has ended" }
    }

    val result =
        try {
          operation(uid)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (failure: Exception) {
          checkSession()
          throw failure
        }
    checkSession()
    return result
  }

  private companion object {
    const val DISPLAY_NAME = "displayName"
  }
}
