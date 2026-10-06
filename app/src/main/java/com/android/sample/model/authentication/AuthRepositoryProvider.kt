package com.android.sample.model.authentication

/**
 * Provides the single [AuthRepository] used by the app.
 *
 * The Firebase implementation is created lazily on first use, so tests can set [repository] to a
 * [FakeAuthRepository] without ever touching Firebase.
 */
object AuthRepositoryProvider {
  private val defaultRepository: AuthRepository by lazy { AuthRepositoryFirebase() }
  private var override: AuthRepository? = null

  var repository: AuthRepository
    get() = override ?: defaultRepository
    set(value) {
      override = value
    }

  /** Restores the default Firebase repository; intended for test cleanup. */
  fun reset() {
    override = null
  }
}
