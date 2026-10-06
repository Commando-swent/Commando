package com.android.sample.model.authentication

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test

/** The provider must hand out an injected repository without initializing Firebase. */
class AuthRepositoryProviderTest {
  @After
  fun restoreDefault() {
    AuthRepositoryProvider.reset()
  }

  @Test
  fun injectedRepositoryIsReturned() {
    val fake = FakeAuthRepository()

    AuthRepositoryProvider.repository = fake

    assertSame(fake, AuthRepositoryProvider.repository)
  }

  @Test
  fun latestInjectedRepositoryWins() {
    val first = FakeAuthRepository()
    val second = FakeAuthRepository()

    AuthRepositoryProvider.repository = first
    AuthRepositoryProvider.repository = second

    assertSame(second, AuthRepositoryProvider.repository)
  }
}
