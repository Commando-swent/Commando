package com.android.sample.ui.profile

import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.ProfileValidation

/** A draft is separate from the confirmed profile, including an email awaiting verification. */
data class ProfileEditUiState(
    val fullName: String,
    val email: String,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val pendingEmail: String? = null,
    val error: AuthException? = null,
) {
  val nameInvalid
    get() = fullName.isBlank()

  val emailInvalid
    get() = !ProfileValidation.isValidEmail(email) || error is AuthException.InvalidEmail

  val canSave
    get() = !isSaving && !saved && !nameInvalid && !emailInvalid
}
