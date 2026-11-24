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
    val publicName: PublicNameEditState? = null,
) {
  val nameInvalid
    get() = fullName.isBlank()

  val emailInvalid
    get() = !ProfileValidation.isValidEmail(email) || error is AuthException.InvalidEmail

  val canSave
    get() = !isSaving && !saved && !nameInvalid && !emailInvalid
}

/** A successful read can confirm an unpublished name. A failed read leaves [loaded] false. */
data class PublicNameEditState(
    val loaded: Boolean = false,
    val confirmed: String? = null,
    val draft: String = "",
    val busy: Boolean = false,
    val failed: Boolean = false,
)
