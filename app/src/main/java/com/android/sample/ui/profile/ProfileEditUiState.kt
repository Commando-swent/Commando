package com.android.sample.ui.profile

import com.android.sample.model.authentication.ProfileValidation

/** Editable draft; it never replaces the confirmed values shown on the Profile screen. */
data class ProfileEditUiState(
    val fullName: String = "",
    val email: String = "",
    val fullNameError: Boolean = false,
    val emailError: Boolean = false,
    val isSaving: Boolean = false,
    val error: ProfileSaveError? = null,
    val pendingEmail: String? = null,
    val saved: Boolean = false,
) {
  val canSave: Boolean
    get() =
        !isSaving &&
            !saved &&
            !fullNameError &&
            !emailError &&
            fullName.isNotBlank() &&
            ProfileValidation.isValidEmail(email)
}

/** Public error categories; provider messages and causes are never presented in the UI. */
enum class ProfileSaveError {
  NETWORK,
  EMAIL_IN_USE,
  RECENT_LOGIN_REQUIRED,
  SESSION_CHANGED,
  UNKNOWN,
}
