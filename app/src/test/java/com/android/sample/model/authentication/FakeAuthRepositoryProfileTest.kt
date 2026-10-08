package com.android.sample.model.authentication

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FakeAuthRepositoryProfileTest {
  private val alice = AuthUser("alice", "Alice Martin", "alice@example.test")

  @Test
  fun defaultUpdateChangesNameWhileEmailRemainsPending() = runTest {
    val repository = FakeAuthRepository(alice)
    val result = repository.updateProfile(" Alice Dupont ", " new@example.test ").getOrThrow()
    assertEquals("Alice Dupont", repository.currentUser?.displayName)
    assertEquals(alice.email, repository.currentUser?.email)
    assertEquals("new@example.test", result.pendingEmail)
  }

  @Test
  fun configuredFailureAndInvalidInputPreserveSession() = runTest {
    val repository = FakeAuthRepository(alice)
    repository.updateProfileResult = Result.failure(AuthException.Network())
    assertTrue(repository.updateProfile("Alice Dupont", "new@example.test").isFailure)
    assertTrue(
        repository.updateProfile("", "alice@example.test").exceptionOrNull()
            is AuthException.InvalidName
    )
    assertTrue(
        repository.updateProfile("Alice", "bad").exceptionOrNull() is AuthException.InvalidEmail
    )
    assertEquals(alice, repository.currentUser)
  }

  @Test
  fun missingUserOrAnotherAccountsConfiguredResultAreRejected() = runTest {
    val repository = FakeAuthRepository()
    assertTrue(
        repository.updateProfile("Alice", "alice@example.test").exceptionOrNull()
            is AuthException.SessionChanged
    )
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    repository.updateProfileResult = Result.success(ProfileUpdateResult(AuthUser("bob")))
    assertTrue(
        repository.updateProfile("Alice", "alice@example.test").exceptionOrNull()
            is AuthException.SessionChanged
    )
    assertEquals(alice, repository.currentUser)
  }

  @Test(expected = IllegalArgumentException::class)
  fun configuredErrorsMustFollowRepositoryContract() {
    FakeAuthRepository().updateProfileResult =
        Result.failure(IllegalStateException("private error"))
  }
}
