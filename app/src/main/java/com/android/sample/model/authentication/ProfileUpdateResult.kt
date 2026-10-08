package com.android.sample.model.authentication

/** Confirmed profile values and, separately, the address awaiting email verification. */
data class ProfileUpdateResult(val user: AuthUser, val pendingEmail: String? = null)

/** Shared validation for profile changes, independent of the UI and Firebase SDK. */
object ProfileValidation {
  private val localPattern = Regex("[^\\s@.]+(?:\\.[^\\s@.]+)*")
  private val domainPattern =
      Regex(
          "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+"
      )

  fun isValidEmail(email: String): Boolean {
    val parts = email.trim().split('@')
    return parts.size == 2 && localPattern.matches(parts[0]) && domainPattern.matches(parts[1])
  }

  internal fun error(fullName: String, email: String): AuthException? =
      when {
        fullName.isBlank() -> AuthException.InvalidName()
        !isValidEmail(email) -> AuthException.InvalidEmail()
        else -> null
      }
}
