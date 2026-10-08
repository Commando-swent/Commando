package com.android.sample.model.authentication

import androidx.credentials.Credential
import kotlinx.coroutines.flow.Flow

/**
 * Authentication operations and session state shared by production and test implementations.
 *
 * Every [Result.failure] returned by this interface holds an [AuthException]. Cancellation of the
 * calling coroutine is rethrown, never wrapped in a [Result].
 */
interface AuthRepository {
  /** Local session snapshot; this does not validate the session with a server. */
  val currentUser: AuthUser?

  /**
   * Observes session state: emits the current user on collection, then each change. Equal
   * consecutive values are not re-emitted; this is not an event history.
   */
  fun observeAuthState(): Flow<AuthUser?>

  /**
   * Creates an account and signs it in. A non-blank [fullName] becomes the user's display name; the
   * new session is only observed once that name is saved.
   */
  suspend fun signUpWithEmail(
      email: String,
      password: String,
      fullName: String? = null,
  ): Result<AuthUser>

  suspend fun signInWithEmail(email: String, password: String): Result<AuthUser>

  suspend fun signInWithGoogle(credential: Credential): Result<AuthUser>

  fun signOut(): Result<Unit>
}
