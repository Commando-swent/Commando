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
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.auth
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    PROFILE,
  }

  // Updating profile fields does not reliably trigger Firebase's authentication listener.
  private val profileUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  override val currentUser: AuthUser?
    get() = auth.currentUser?.toAuthUser()

  override fun observeAuthState(): Flow<AuthUser?> = callbackFlow {
    // Emit the current session immediately; Firebase's own initial callback is deduplicated.
    trySend(auth.currentUser?.toAuthUser())
    val updates =
        launch(start = CoroutineStart.UNDISPATCHED) {
          profileUpdates.collect { trySend(auth.currentUser?.toAuthUser()) }
        }
    val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toAuthUser()) }
    auth.addAuthStateListener(listener)
    awaitClose {
      updates.cancel()
      auth.removeAuthStateListener(listener)
    }
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

  override suspend fun updateProfile(fullName: String, email: String): Result<ProfileUpdateResult> {
    currentCoroutineContext().ensureActive()
    ProfileValidation.error(fullName, email)?.let {
      return Result.failure(it)
    }
    val user = auth.currentUser ?: return Result.failure(AuthException.SessionChanged())
    val requestedName = fullName.trim()
    val requestedEmail = email.trim()
    return try {
      ensureSameAccount(user)
      if (user.displayName != requestedName) {
        user
            .updateProfile(UserProfileChangeRequest.Builder().setDisplayName(requestedName).build())
            .await()
        currentCoroutineContext().ensureActive()
        ensureSameAccount(user)
        profileUpdates.tryEmit(Unit)
      }
      val pendingEmail = requestedEmail.takeUnless { it == user.email }
      if (pendingEmail != null) {
        user.verifyBeforeUpdateEmail(pendingEmail).await()
        currentCoroutineContext().ensureActive()
        ensureSameAccount(user)
      }
      val confirmedUser =
          auth.currentUser?.takeIf { it.uid == user.uid } ?: throw AuthException.SessionChanged()
      Result.success(
          ProfileUpdateResult(
              confirmedUser.toAuthUser(),
              pendingEmail.takeUnless { it == confirmedUser.email },
          )
      )
    } catch (error: CancellationException) {
      currentCoroutineContext().ensureActive()
      Result.failure(AuthException.Unknown(error))
    } catch (error: Exception) {
      // Reflect a name update that succeeded even if the separate email request failed.
      profileUpdates.tryEmit(Unit)
      Result.failure(
          if (error is AuthException) error else error.toAuthException(Operation.PROFILE)
      )
    }
  }

  private fun ensureSameAccount(user: FirebaseUser) {
    if (auth.currentUser?.uid != user.uid) throw AuthException.SessionChanged()
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
        is GoogleIdTokenParsingException -> AuthException.InvalidGoogleCredential(this)
        is FirebaseNetworkException -> AuthException.Network(this)
        is FirebaseTooManyRequestsException -> AuthException.TooManyRequests(this)
        is FirebaseAuthRecentLoginRequiredException -> AuthException.RequiresRecentLogin(this)
        is FirebaseAuthWeakPasswordException -> AuthException.Unknown(this)
        is FirebaseAuthUserCollisionException ->
            if (
                (operation == Operation.SIGN_UP || operation == Operation.PROFILE) &&
                    errorCode == ERROR_EMAIL_ALREADY_IN_USE
            ) {
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
