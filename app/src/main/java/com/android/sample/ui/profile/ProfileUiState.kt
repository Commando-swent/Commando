package com.android.sample.ui.profile

/**
 * The states the Profile screen can display. Loading and missing-user states contain no profile
 * information, so the UI cannot accidentally display a previous account's data or sample values.
 */
sealed interface ProfileUiState {
  /** The authentication flow has not emitted its first value yet. */
  data object Loading : ProfileUiState

  /** No user is signed in; the existing application navigation handles the return to Login. */
  data object MissingUser : ProfileUiState

  /**
   * Information supplied by the repository for the signed-in user. Null fields indicate missing
   * information; the screen decides how to present them without inventing user data.
   */
  data class Content(val fullName: String?, val email: String?) : ProfileUiState
}
