package com.android.sample.data.repository

// AI assistance: Claude Code.
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Domain-independent category of a failed Firestore operation; each repository maps its own. */
internal enum class FirestoreFailure {
  PERMISSION_DENIED,
  UNAUTHENTICATED,
  NETWORK_UNAVAILABLE,
  NOT_FOUND,
  INVALID_DATA,
  UNKNOWN,
}

/** Classifies this exception itself; a Firestore exception wrapped as a cause stays UNKNOWN. */
internal fun Exception.toFirestoreFailure(): FirestoreFailure =
    when (this) {
      is FirebaseFirestoreException ->
          when (code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> FirestoreFailure.PERMISSION_DENIED
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> FirestoreFailure.UNAUTHENTICATED
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                FirestoreFailure.NETWORK_UNAVAILABLE
            FirebaseFirestoreException.Code.NOT_FOUND -> FirestoreFailure.NOT_FOUND
            FirebaseFirestoreException.Code.INVALID_ARGUMENT,
            FirebaseFirestoreException.Code.OUT_OF_RANGE,
            FirebaseFirestoreException.Code.DATA_LOSS -> FirestoreFailure.INVALID_DATA
            else -> FirestoreFailure.UNKNOWN
          }
      is IOException -> FirestoreFailure.NETWORK_UNAVAILABLE
      else -> FirestoreFailure.UNKNOWN
    }

/**
 * Runs [operation] and turns any [Exception] it throws into [onFailure]'s result. Coroutine
 * cancellation is rethrown, never reported as a failure.
 */
internal suspend fun <T> runFirestoreOperation(
    onFailure: (FirestoreFailure) -> T,
    operation: suspend () -> T,
): T =
    try {
      operation()
    } catch (exception: CancellationException) {
      throw exception
    } catch (exception: Exception) {
      onFailure(exception.toFirestoreFailure())
    }
