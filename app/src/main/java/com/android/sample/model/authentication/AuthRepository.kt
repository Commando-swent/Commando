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

  suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser>

  suspend fun signInWithEmail(email: String, password: String): Result<AuthUser>

  suspend fun signInWithGoogle(credential: Credential): Result<AuthUser>

  /**
   * Saves the signed-in user's name and requests verification before changing their email.
   * [ProfileUpdateResult.user] contains confirmed values; a requested email is separate.
   */
  suspend fun updateProfile(fullName: String, email: String): Result<ProfileUpdateResult>

  fun signOut(): Result<Unit>
}
