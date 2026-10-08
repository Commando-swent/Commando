package com.android.sample.model.authentication

import androidx.credentials.Credential
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory authentication for JVM and instrumented tests, with independent state per instance.
 *
 * Configure each operation's result before calling it. Results remain configured across calls;
 * failures preserve the session, and successes replace it. Arguments are neither inspected nor
 * retained. Unconfigured authentication fails with [AuthException.Unknown]; sign-out succeeds.
 *
 * Observers immediately receive the current session. StateFlow conflates equal values, and slow
 * observers may skip intermediate states. This is session state, not an event history.
 */
class FakeAuthRepository(initialUser: AuthUser? = null) : AuthRepository {
  private val session = MutableStateFlow(initialUser)
  private val authState = session.asStateFlow()

  override val currentUser: AuthUser?
    get() = session.value

  var signUpWithEmailResult: Result<AuthUser> = Result.failure(AuthException.Unknown())
    set(value) {
      field = validateResult(value)
    }

  var signInWithEmailResult: Result<AuthUser> = Result.failure(AuthException.Unknown())
    set(value) {
      field = validateResult(value)
    }

  var signInWithGoogleResult: Result<AuthUser> = Result.failure(AuthException.Unknown())
    set(value) {
      field = validateResult(value)
    }

  var signOutResult: Result<Unit> = Result.success(Unit)
    set(value) {
      field = validateResult(value)
    }

  /** Null uses a successful local update; configured errors keep the existing session. */
  var updateProfileResult: Result<ProfileUpdateResult>? = null
    set(value) {
      field = value?.let { validateResult(it) }
    }

  override fun observeAuthState(): Flow<AuthUser?> = authState

  override suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser> =
      authenticate(signUpWithEmailResult)

  override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> =
      authenticate(signInWithEmailResult)

  override suspend fun signInWithGoogle(credential: Credential): Result<AuthUser> =
      authenticate(signInWithGoogleResult)

  override suspend fun updateProfile(fullName: String, email: String): Result<ProfileUpdateResult> {
    currentCoroutineContext().ensureActive()
    ProfileValidation.error(fullName, email)?.let {
      return Result.failure(it)
    }
    val user = currentUser ?: return Result.failure(AuthException.SessionChanged())
    val requestedEmail = email.trim()
    val result =
        updateProfileResult
            ?: Result.success(
                ProfileUpdateResult(
                    user.copy(displayName = fullName.trim()),
                    requestedEmail.takeUnless { it == user.email },
                )
            )
    return result.fold(
        onSuccess = { updated ->
          if (updated.user.uid != user.uid) Result.failure(AuthException.SessionChanged())
          else {
            session.value = updated.user
            Result.success(updated)
          }
        },
        onFailure = { Result.failure(it) },
    )
  }

  override fun signOut(): Result<Unit> {
    return signOutResult.onSuccess { session.value = null }
  }

  private suspend fun authenticate(result: Result<AuthUser>): Result<AuthUser> {
    currentCoroutineContext().ensureActive()
    return result.onSuccess { user -> session.value = user }
  }

  private fun <T> validateResult(result: Result<T>): Result<T> {
    require(result.isSuccess || result.exceptionOrNull() is AuthException) {
      "A configured failure must contain an AuthException."
    }
    return result
  }
}
