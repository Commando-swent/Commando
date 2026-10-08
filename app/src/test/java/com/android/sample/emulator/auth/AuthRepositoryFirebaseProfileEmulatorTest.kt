package com.android.sample.emulator.auth

import com.android.sample.emulator.auth.FirebaseAuthEmulator.runTest
import com.android.sample.model.authentication.AuthRepositoryFirebase
import com.google.firebase.auth.FirebaseAuth
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real SDK profile writes against demo-commando only; the production project is never used. */
@RunWith(RobolectricTestRunner::class)
class AuthRepositoryFirebaseProfileEmulatorTest {
  private lateinit var auth: FirebaseAuth
  private lateinit var repository: AuthRepositoryFirebase

  @Before
  fun setUp() {
    auth = FirebaseAuthEmulator.connect()
    FirebaseAuthEmulator.clearAccounts()
    auth.signOut()
    repository = AuthRepositoryFirebase(auth)
  }

  @After
  fun tearDown() {
    auth.signOut()
  }

  @Test
  fun fullNamePersistsAcrossSignOutAndSignIn() = runTest {
    val created = repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    val updated = repository.updateProfile(" Alice Dupont ", EMAIL).getOrThrow()
    assertEquals(created.uid, updated.user.uid)
    assertEquals("Alice Dupont", updated.user.displayName)
    assertNull(updated.pendingEmail)
    repository.signOut().getOrThrow()
    val restored = repository.signInWithEmail(EMAIL, PASSWORD).getOrThrow()
    assertEquals("Alice Dupont", restored.displayName)
    assertEquals(EMAIL, restored.email)
  }

  @Test
  fun verificationRequestLeavesConfirmedEmailUnchanged() = runTest {
    repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    val updated = repository.updateProfile("Alice Dupont", "new@example.test").getOrThrow()
    assertEquals("new@example.test", updated.pendingEmail)
    assertEquals(EMAIL, updated.user.email)
    assertEquals(EMAIL, repository.currentUser?.email)
    repository.signOut().getOrThrow()
    val restored = repository.signInWithEmail(EMAIL, PASSWORD).getOrThrow()
    assertEquals(EMAIL, restored.email)
    assertEquals("Alice Dupont", restored.displayName)
  }

  private companion object {
    const val EMAIL = "alice@example.test"
    const val PASSWORD = "example-password-1"
  }
}
