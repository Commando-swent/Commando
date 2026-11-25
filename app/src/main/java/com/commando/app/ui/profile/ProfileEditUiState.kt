package com.commando.app.ui.profile

import com.commando.app.data.repository.isValidPublicName
import com.commando.app.model.authentication.AuthException
import com.commando.app.model.authentication.ProfileValidation

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

/**
 * Public-name input independent of private account details. [loaded] distinguishes an unknown name
 * from a confirmed missing profile. [confirmed] changes only after a successful repository
 * operation; [draft] remains editable input and is retained after failed writes. [failed] reports a
 * repository failure, independently of input validation.
 */
data class PublicNameEditState(
    val loaded: Boolean = false,
    val confirmed: String? = null,
    val draft: String = "",
    val busy: Boolean = false,
    val failed: Boolean = false,
) {
  /** Whether [draft] violates the shared public-name contract; input is never normalized. */
  val nameInvalid
    get() = !isValidPublicName(draft)

  /** Whether an explicit publication or update can be submitted. Removal is a separate action. */
  val canSave
    get() = loaded && !busy && !nameInvalid && draft != confirmed
}
