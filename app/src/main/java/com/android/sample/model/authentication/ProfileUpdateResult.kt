package com.android.sample.model.authentication

/** Confirmed profile values and, separately, the address awaiting email verification. */
data class ProfileUpdateResult(val user: AuthUser, val pendingEmail: String? = null)

/** Shared validation for profile changes, independent of the UI and Firebase SDK. */
object ProfileValidation {
  private val emailPattern =
      Regex(
          "^[^\\s@.]+(?:\\.[^\\s@.]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?" +
              "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$"
      )

  fun isValidEmail(email: String): Boolean = emailPattern.matches(email.trim())

  internal fun error(fullName: String, email: String): AuthException? =
      when {
        fullName.isBlank() -> AuthException.InvalidName()
        !isValidEmail(email) -> AuthException.InvalidEmail()
        else -> null
      }
}
