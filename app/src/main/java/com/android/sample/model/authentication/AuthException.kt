package com.android.sample.model.authentication

/**
 * Authentication failures; [cause] keeps the provider error for debugging and is never shown to
 * users. Compare by type (`is` / `when`), never with `equals`.
 */
sealed class AuthException(cause: Throwable? = null) : Exception(cause) {
  class InvalidEmail(cause: Throwable? = null) : AuthException(cause)

  class EmailAlreadyInUse(cause: Throwable? = null) : AuthException(cause)

  class AccountConflict(cause: Throwable? = null) : AuthException(cause)

  class InvalidCredentials(cause: Throwable? = null) : AuthException(cause)

  class InvalidGoogleCredential(cause: Throwable? = null) : AuthException(cause)

  class Network(cause: Throwable? = null) : AuthException(cause)

  class TooManyRequests(cause: Throwable? = null) : AuthException(cause)

  class Unknown(cause: Throwable? = null) : AuthException(cause)
}
