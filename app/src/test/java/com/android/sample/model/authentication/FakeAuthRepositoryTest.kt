package com.android.sample.model.authentication

import android.os.Bundle
import androidx.credentials.CustomCredential
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Contract tests: inputs are opaque and configured outcomes determine the fake's session. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FakeAuthRepositoryTest {
  private val alice =
      AuthUser("alice", "Alice Example", "alice@example.test", "https://example.test/alice.png")
  private val bob =
      AuthUser("bob", "Bob Example", "bob@example.test", "https://example.test/bob.png")

  private val errors =
      listOf(
          AuthException.InvalidEmail(),
          AuthException.EmailAlreadyInUse(),
          AuthException.AccountConflict(),
          AuthException.InvalidCredentials(),
          AuthException.InvalidGoogleCredential(),
          AuthException.Network(),
          AuthException.TooManyRequests(),
          AuthException.Unknown(),
      )

  private enum class Operation {
    SIGN_UP,
    EMAIL,
    GOOGLE;

    fun configure(repository: FakeAuthRepository, result: Result<AuthUser>) {
      when (this) {
        SIGN_UP -> repository.signUpWithEmailResult = result
        EMAIL -> repository.signInWithEmailResult = result
        GOOGLE -> repository.signInWithGoogleResult = result
      }
    }

    fun configuredResult(repository: FakeAuthRepository): Result<AuthUser> =
        when (this) {
          SIGN_UP -> repository.signUpWithEmailResult
          EMAIL -> repository.signInWithEmailResult
          GOOGLE -> repository.signInWithGoogleResult
        }

    suspend fun authenticate(repository: FakeAuthRepository): Result<AuthUser> =
        when (this) {
          // Deliberately invalid input verifies the fake does not implement provider validation.
          SIGN_UP -> repository.signUpWithEmail("not an email", "")
          EMAIL -> repository.signInWithEmail("", " ")
          GOOGLE ->
              repository.signInWithGoogle(CustomCredential("opaque.test.credential", Bundle()))
        }
  }

  private fun TestScope.observe(repository: AuthRepository): MutableList<AuthUser?> {
    val emissions = mutableListOf<AuthUser?>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      repository.observeAuthState().collect {
        assertEquals("Snapshot and observed session must agree", it, repository.currentUser)
        emissions.add(it)
      }
    }
    return emissions
  }

  @Test
  fun defaultSessionIsNullAndDefaultOperationsArePersistent() = runTest {
    val repository = FakeAuthRepository()
    val emissions = observe(repository)

    assertNull(repository.currentUser)
    assertEquals(listOf<AuthUser?>(null), emissions)
    repeat(2) {
      Operation.entries.forEach { operation ->
        assertTrue(
            operation.configuredResult(repository).exceptionOrNull() is AuthException.Unknown
        )
        assertTrue(operation.authenticate(repository).exceptionOrNull() is AuthException.Unknown)
      }
      assertEquals(Result.success(Unit), repository.signOutResult)
      assertEquals(Result.success(Unit), repository.signOut())
    }
    assertNull(repository.currentUser)
    assertEquals(listOf<AuthUser?>(null), emissions)
  }

  @Test
  fun initialSessionIsImmediatelyAvailableAndDefaultFailuresPreserveIt() = runTest {
    val repository = FakeAuthRepository(alice)
    assertEquals(alice, repository.currentUser)
    val emissions = observe(repository)

    Operation.entries.forEach { operation ->
      assertTrue(operation.authenticate(repository).exceptionOrNull() is AuthException.Unknown)
      assertEquals(alice, repository.currentUser)
    }
    assertEquals(listOf(alice), emissions)
  }

  @Test fun signUpSuccessReplacesSession() = verifySuccess(Operation.SIGN_UP)

  @Test fun emailSignInSuccessReplacesSession() = verifySuccess(Operation.EMAIL)

  @Test fun googleSignInSuccessReplacesSession() = verifySuccess(Operation.GOOGLE)

  private fun verifySuccess(operation: Operation) = runTest {
    for (initialUser in listOf(null, alice)) {
      val repository = FakeAuthRepository(initialUser)
      val emissions = observe(repository)
      val expected = Result.success(bob)
      operation.configure(repository, expected)
      assertEquals(initialUser, repository.currentUser)
      assertEquals(listOf(initialUser), emissions)

      repeat(2) {
        assertEquals(expected, operation.authenticate(repository))
        assertEquals(bob, repository.currentUser)
        assertEquals(expected, operation.configuredResult(repository))
      }
      assertEquals(listOf(initialUser, bob), emissions)

      assertEquals(Result.success(Unit), repository.signOut())
      assertEquals(expected, operation.authenticate(repository))
      assertEquals(bob, repository.currentUser)
      assertEquals(listOf(initialUser, bob, null, bob), emissions)
    }
  }

  @Test fun signUpSupportsEveryTypedFailure() = verifyFailures(Operation.SIGN_UP)

  @Test fun emailSignInSupportsEveryTypedFailure() = verifyFailures(Operation.EMAIL)

  @Test fun googleSignInSupportsEveryTypedFailure() = verifyFailures(Operation.GOOGLE)

  private fun verifyFailures(operation: Operation) = runTest {
    for (initialUser in listOf(null, alice)) {
      val repository = FakeAuthRepository(initialUser)
      val emissions = observe(repository)
      for (error in errors) {
        operation.configure(repository, Result.failure(error))
        repeat(2) {
          assertSame(error, operation.authenticate(repository).exceptionOrNull())
          assertSame(error, operation.configuredResult(repository).exceptionOrNull())
          assertEquals(initialUser, repository.currentUser)
        }
      }
      assertEquals(listOf(initialUser), emissions)
    }
  }

  @Test
  fun configuredFailuresKeepTheirCause() = runTest {
    val repository = FakeAuthRepository(alice)
    val cause = IOException("offline")
    val error = AuthException.Network(cause = cause)
    for (operation in Operation.entries) {
      operation.configure(repository, Result.failure(error))
      val returned = operation.authenticate(repository).exceptionOrNull()
      assertSame(error, returned)
      assertSame(cause, returned?.cause)
    }
    repository.signOutResult = Result.failure(error)
    val returned = repository.signOut().exceptionOrNull()
    assertSame(error, returned)
    assertSame(cause, returned?.cause)
    assertEquals(alice, repository.currentUser)
  }

  @Test
  fun signOutSupportsEveryTypedFailureAndRecovery() = runTest {
    val repository = FakeAuthRepository(alice)
    val emissions = observe(repository)
    errors.forEach { error ->
      repository.signOutResult = Result.failure(error)
      repeat(2) {
        assertSame(error, repository.signOut().exceptionOrNull())
        assertSame(error, repository.signOutResult.exceptionOrNull())
        assertEquals(alice, repository.currentUser)
      }
    }
    assertEquals(listOf(alice), emissions)

    repository.signOutResult = Result.success(Unit)
    assertEquals(alice, repository.currentUser)
    repeat(2) { assertEquals(Result.success(Unit), repository.signOut()) }
    assertNull(repository.currentUser)
    assertEquals(listOf(alice, null), emissions)
  }

  @Test
  fun untypedFailuresAreRejectedWithoutReplacingAnyConfiguredResult() = runTest {
    val repository = FakeAuthRepository(alice)
    val emissions = observe(repository)
    val rejectedErrors = listOf(IllegalStateException("not an auth error"), CancellationException())
    for (operation in Operation.entries) {
      for (previous in
          listOf(Result.success(bob), Result.failure<AuthUser>(AuthException.Network()))) {
        operation.configure(repository, previous)
        for (error in rejectedErrors) {
          assertThrows(IllegalArgumentException::class.java) {
            operation.configure(repository, Result.failure(error))
          }
          assertEquals(previous, operation.configuredResult(repository))
        }
      }
    }
    for (previous in listOf(Result.success(Unit), Result.failure<Unit>(AuthException.Network()))) {
      repository.signOutResult = previous
      for (error in rejectedErrors) {
        assertThrows(IllegalArgumentException::class.java) {
          repository.signOutResult = Result.failure(error)
        }
        assertEquals(previous, repository.signOutResult)
      }
    }
    assertEquals(alice, repository.currentUser)
    assertEquals(listOf(alice), emissions)
  }

  @Test
  fun observersReceiveLatestSessionAndContinueIndependently() = runTest {
    val repository = FakeAuthRepository(alice)
    val first = mutableListOf<AuthUser?>()
    val firstSubscription =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeAuthState().collect { first.add(it) }
        }
    val second = observe(repository)
    repository.signUpWithEmailResult = Result.success(bob)
    repository.signUpWithEmail("", "")
    val late = observe(repository)
    assertEquals(listOf(bob), late)

    firstSubscription.cancel()
    repository.signOut()
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")

    assertEquals(listOf(alice, bob), first)
    assertEquals(listOf(alice, bob, null, alice), second)
    assertEquals(listOf(bob, null, alice), late)
    assertEquals(alice, repository.currentUser)
  }

  @Test
  fun equalUsersAreSuppressedButProfileChangesAndNullableFieldsAreObserved() = runTest {
    val repository = FakeAuthRepository(alice)
    val emissions = observe(repository)
    val changedProfile = alice.copy(displayName = "Updated", email = null, photoUrl = null)
    repository.signInWithEmailResult = Result.success(alice.copy())
    assertEquals(Result.success(alice), repository.signInWithEmail("", ""))
    assertEquals(listOf(alice), emissions)

    repository.signInWithEmailResult = Result.success(changedProfile)
    assertEquals(Result.success(changedProfile), repository.signInWithEmail("", ""))
    assertEquals(changedProfile, repository.currentUser)
    assertEquals(listOf(alice, changedProfile), emissions)

    val minimalUser = AuthUser("minimal")
    assertNull(minimalUser.displayName)
    assertNull(minimalUser.email)
    assertNull(minimalUser.photoUrl)
    repository.signInWithEmailResult = Result.success(minimalUser)
    assertEquals(Result.success(minimalUser), repository.signInWithEmail("", ""))
    assertEquals(minimalUser, repository.currentUser)
    assertEquals(listOf(alice, changedProfile, minimalUser), emissions)
  }

  @Test
  fun configurationsAreIndependentAcrossOperationsAndInstances() = runTest {
    val first = FakeAuthRepository(alice)
    val second = FakeAuthRepository()
    val firstEmissions = observe(first)
    val secondEmissions = observe(second)
    first.signUpWithEmailResult = Result.success(bob)
    val invalidCredentials = AuthException.InvalidCredentials()
    val network = AuthException.Network()
    first.signInWithEmailResult = Result.failure(invalidCredentials)
    first.signInWithGoogleResult = Result.success(alice)
    first.signOutResult = Result.failure(network)

    assertEquals(Result.success(bob), Operation.SIGN_UP.authenticate(first))
    assertSame(invalidCredentials, Operation.EMAIL.authenticate(first).exceptionOrNull())
    assertSame(network, first.signOut().exceptionOrNull())
    assertEquals(bob, first.currentUser)
    assertEquals(Result.success(alice), Operation.GOOGLE.authenticate(first))
    Operation.entries.forEach { operation ->
      assertTrue(operation.authenticate(second).exceptionOrNull() is AuthException.Unknown)
    }
    assertEquals(Result.success(Unit), second.signOut())
    assertEquals(listOf(alice, bob, alice), firstEmissions)
    assertEquals(listOf<AuthUser?>(null), secondEmissions)

    second.signInWithEmailResult = Result.success(bob)
    assertEquals(Result.success(bob), Operation.EMAIL.authenticate(second))
    assertEquals(alice, first.currentUser)
    assertEquals(bob, second.currentUser)
    assertSame(invalidCredentials, first.signInWithEmailResult.exceptionOrNull())
    assertEquals(listOf(alice, bob, alice), firstEmissions)
    assertEquals(listOf(null, bob), secondEmissions)
  }

  @Test
  fun cancelledAuthenticationPropagatesCancellationAndPreservesSession() = runTest {
    for (operation in Operation.entries) {
      for (configured in
          listOf(Result.success(bob), Result.failure<AuthUser>(AuthException.Network()))) {
        val repository = FakeAuthRepository(alice)
        val emissions = observe(repository)
        operation.configure(repository, configured)
        var attempted = false
        var cancellationCaught = false
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
              currentCoroutineContext().cancel()
              attempted = true
              try {
                operation.authenticate(repository)
                fail("$operation must propagate cancellation instead of returning a Result")
              } catch (_: CancellationException) {
                cancellationCaught = true
              }
            }
        job.join()
        assertTrue("$operation was invoked in a cancelled coroutine", attempted)
        assertTrue("$operation propagated cancellation", cancellationCaught)
        assertEquals(alice, repository.currentUser)
        assertEquals(listOf(alice), emissions)
        assertEquals(configured, operation.configuredResult(repository))
      }
    }
  }
}
