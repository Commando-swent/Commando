package com.android.sample.model.authentication

// AI assistance: Claude (Anthropic).
import androidx.credentials.Credential
import com.google.firebase.Firebase
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await

/**
 * Firebase implementation of [AuthRepository].
 *
 * Converts [FirebaseUser] into [AuthUser] and Firebase errors into [AuthException], keeping the
 * original error as the cause. Passwords and tokens are never logged.
 *
 * Google sign-in is added by a later sub-issue of #6.
 *
 * @param auth The [FirebaseAuth] instance used for authentication.
 */
class AuthRepositoryFirebase(private val auth: FirebaseAuth = Firebase.auth) : AuthRepository {

  private enum class Operation {
    SIGN_UP,
    SIGN_IN_EMAIL,
  }

  override val currentUser: AuthUser?
    get() = auth.currentUser?.toAuthUser()

  override fun observeAuthState(): Flow<AuthUser?> = callbackFlow {
    // Emit the current session immediately; Firebase's own initial callback is deduplicated.
    trySend(auth.currentUser?.toAuthUser())
    val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toAuthUser()) }
    auth.addAuthStateListener(listener)
    awaitClose { auth.removeAuthStateListener(listener) }
  }
      .conflate()
      .distinctUntilChanged()

  override suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser> =
      authenticate(Operation.SIGN_UP) {
        auth.createUserWithEmailAndPassword(email.trim(), password).await()
      }

  override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> =
      authenticate(Operation.SIGN_IN_EMAIL) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
      }

  override suspend fun signInWithGoogle(credential: Credential): Result<AuthUser> =
      TODO("Google sign-in is added by a later sub-issue of #6.")

  override fun signOut(): Result<Unit> =
      try {
        auth.signOut()
        Result.success(Unit)
      } catch (e: Exception) {
        Result.failure(AuthException.Unknown(e))
      }

  private suspend fun authenticate(
      operation: Operation,
      request: suspend () -> AuthResult,
  ): Result<AuthUser> =
      try {
        val user = request().user
        if (user == null) Result.failure(AuthException.Unknown())
        else Result.success(user.toAuthUser())
      } catch (e: CancellationException) {
        // Rethrow only if the caller was cancelled; a task cancelled by Firebase is a failure.
        currentCoroutineContext().ensureActive()
        Result.failure(AuthException.Unknown(e))
      } catch (e: Exception) {
        Result.failure(e.toAuthException(operation))
      }

  private fun FirebaseUser.toAuthUser() =
      AuthUser(uid = uid, displayName = displayName, email = email, photoUrl = photoUrl?.toString())

  // Subclasses are checked before their superclasses.
  private fun Exception.toAuthException(operation: Operation): AuthException =
      when (this) {
        is FirebaseNetworkException -> AuthException.Network(this)
        is FirebaseTooManyRequestsException -> AuthException.TooManyRequests(this)
        is FirebaseAuthWeakPasswordException -> AuthException.Unknown(this)
        is FirebaseAuthUserCollisionException ->
            if (operation == Operation.SIGN_UP && errorCode == ERROR_EMAIL_ALREADY_IN_USE) {
              AuthException.EmailAlreadyInUse(this)
            } else {
              AuthException.AccountConflict(this)
            }
        is FirebaseAuthInvalidCredentialsException ->
            if (errorCode == ERROR_INVALID_EMAIL) AuthException.InvalidEmail(this)
            else AuthException.InvalidCredentials(this)
        is FirebaseAuthInvalidUserException -> AuthException.InvalidCredentials(this)
        else -> AuthException.Unknown(this)
      }

  private companion object {
    const val ERROR_EMAIL_ALREADY_IN_USE = "ERROR_EMAIL_ALREADY_IN_USE"
    const val ERROR_INVALID_EMAIL = "ERROR_INVALID_EMAIL"
  }
}
