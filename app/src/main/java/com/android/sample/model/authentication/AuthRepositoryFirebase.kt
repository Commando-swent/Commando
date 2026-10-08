package com.android.sample.model.authentication

// AI assistance: Claude (Anthropic).
import androidx.credentials.Credential
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
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
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.auth
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Firebase implementation of [AuthRepository].
 *
 * Converts [FirebaseUser] into [AuthUser] and Firebase errors into [AuthException], keeping the
 * original error as the cause. Passwords and tokens are never logged. Clearing the Credential
 * Manager state on sign-out belongs to the UI integration (#7), not to this repository.
 *
 * @param auth The [FirebaseAuth] instance used for authentication.
 * @param helper Extracts Google ID tokens and converts them to Firebase credentials.
 */
class AuthRepositoryFirebase(
    private val auth: FirebaseAuth = Firebase.auth,
    private val helper: GoogleSignInHelper = DefaultGoogleSignInHelper(),
) : AuthRepository {

  private enum class Operation {
    SIGN_UP,
    SIGN_IN_EMAIL,
    GOOGLE,
  }

  override val currentUser: AuthUser?
    get() = auth.currentUser?.toAuthUser()

  // True while a new account waits for its display name, so observers do not see it unnamed.
  private val signingUp = MutableStateFlow(false)

  override fun observeAuthState(): Flow<AuthUser?> = callbackFlow {
    fun publish() = trySend(if (signingUp.value) null else auth.currentUser?.toAuthUser())
    // Emit the current session immediately; Firebase's own initial callback is deduplicated.
    publish()
    val listener = FirebaseAuth.AuthStateListener { publish() }
    auth.addAuthStateListener(listener)
    // Firebase does not notify profile changes, so the end of a sign-up republishes the user.
    // The current value is not dropped: a sign-up may end before this collector subscribes, and
    // a repeated state is removed by distinctUntilChanged.
    launch { signingUp.collect { publish() } }
    awaitClose { auth.removeAuthStateListener(listener) }
  }
      .conflate()
      .distinctUntilChanged()

  override suspend fun signUpWithEmail(
      email: String,
      password: String,
      fullName: String?,
  ): Result<AuthUser> {
    signingUp.value = true
    return try {
      authenticate(Operation.SIGN_UP) {
        auth.createUserWithEmailAndPassword(email.trim(), password).await().also { result ->
          fullName?.trim()?.takeIf { it.isNotEmpty() }?.let { result.user?.saveDisplayName(it) }
        }
      }
    } finally {
      signingUp.value = false
    }
  }

  override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> =
      authenticate(Operation.SIGN_IN_EMAIL) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
      }

  override suspend fun signInWithGoogle(credential: Credential): Result<AuthUser> {
    if (
        credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) {
      return Result.failure(AuthException.InvalidGoogleCredential())
    }
    return authenticate(Operation.GOOGLE) {
      val idToken = helper.extractIdTokenCredential(credential.data).idToken
      auth.signInWithCredential(helper.toFirebaseCredential(idToken)).await()
    }
  }

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

  /** The account already exists at this point, so a failure here must not fail the sign-up. */
  private suspend fun FirebaseUser.saveDisplayName(name: String) {
    try {
      updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build()).await()
    } catch (e: CancellationException) {
      currentCoroutineContext().ensureActive()
    } catch (_: Exception) {
      // ponytail: the user stays unnamed; surface a retry if this happens in practice.
    }
  }

  private fun FirebaseUser.toAuthUser() =
      AuthUser(uid = uid, displayName = displayName, email = email, photoUrl = photoUrl?.toString())

  // Subclasses are checked before their superclasses.
  private fun Exception.toAuthException(operation: Operation): AuthException =
      when (this) {
        is GoogleIdTokenParsingException -> AuthException.InvalidGoogleCredential(this)
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
            when {
              errorCode == ERROR_INVALID_EMAIL -> AuthException.InvalidEmail(this)
              operation == Operation.GOOGLE -> AuthException.InvalidGoogleCredential(this)
              else -> AuthException.InvalidCredentials(this)
            }
        is FirebaseAuthInvalidUserException -> AuthException.InvalidCredentials(this)
        else -> AuthException.Unknown(this)
      }

  private companion object {
    const val ERROR_EMAIL_ALREADY_IN_USE = "ERROR_EMAIL_ALREADY_IN_USE"
    const val ERROR_INVALID_EMAIL = "ERROR_INVALID_EMAIL"
  }
}
