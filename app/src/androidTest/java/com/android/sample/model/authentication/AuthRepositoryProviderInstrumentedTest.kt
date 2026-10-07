package com.android.sample.model.authentication

// AI assistance: Claude (Anthropic).
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The default provider repository needs a real FirebaseApp; it makes no network request. */
@RunWith(AndroidJUnit4::class)
class AuthRepositoryProviderInstrumentedTest {
  @After
  fun restoreDefault() {
    AuthRepositoryProvider.reset()
  }

  @Test
  fun defaultRepositoryIsSingleFirebaseInstance() {
    val repository = AuthRepositoryProvider.repository

    assertTrue(repository is AuthRepositoryFirebase)
    assertSame(repository, AuthRepositoryProvider.repository)
  }

  @Test
  fun resetRestoresDefaultAfterInjection() {
    val default = AuthRepositoryProvider.repository
    AuthRepositoryProvider.repository = FakeAuthRepository()

    AuthRepositoryProvider.reset()

    assertSame(default, AuthRepositoryProvider.repository)
  }
}
