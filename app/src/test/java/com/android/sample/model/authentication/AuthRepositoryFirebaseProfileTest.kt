package com.android.sample.model.authentication

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Firebase Task tests: these exercise production SDK calls rather than the fake repository. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthRepositoryFirebaseProfileTest {
  private val auth = mockk<FirebaseAuth>()
  private val user = mockk<FirebaseUser>()
  private var current: FirebaseUser? = user
  private var name: String? = "Alice Martin"
  private val repository = AuthRepositoryFirebase(auth)

  @Before
  fun setUp() {
    every { auth.currentUser } answers { current }
    every { user.uid } returns "alice"
    every { user.displayName } answers { name }
    every { user.email } returns "alice@example.test"
    every { user.photoUrl } returns null
    every { auth.addAuthStateListener(any()) } just Runs
    every { auth.removeAuthStateListener(any()) } just Runs
    every { user.updateProfile(any()) } answers
        {
          name = firstArg<UserProfileChangeRequest>().displayName
          Tasks.forResult<Void>(null)
        }
    every { user.verifyBeforeUpdateEmail(any()) } returns Tasks.forResult<Void>(null)
  }

  @Test
  fun nameOnlyUpdateUsesProfileTaskAndConfirmedSnapshot() = runTest {
    val result = repository.updateProfile("  Alice Dupont  ", " alice@example.test ").getOrThrow()

    assertEquals("Alice Dupont", result.user.displayName)
    assertEquals("alice@example.test", result.user.email)
    assertNull(result.pendingEmail)
    verify(exactly = 1) { user.updateProfile(any()) }
    verify(exactly = 0) { user.verifyBeforeUpdateEmail(any()) }
  }

  @Test
  fun changedEmailRequestsVerificationAndNeverClaimsNewAddressConfirmed() = runTest {
    val result = repository.updateProfile("Alice Martin", " new@example.test ").getOrThrow()

    assertEquals("new@example.test", result.pendingEmail)
    assertEquals("alice@example.test", result.user.email)
    verify(exactly = 1) { user.verifyBeforeUpdateEmail("new@example.test") }
    verify(exactly = 0) { user.updateProfile(any()) }
  }

  @Test
  fun unchangedValuesDoNotStartTasks() = runTest {
    assertTrue(repository.updateProfile("Alice Martin", "alice@example.test").isSuccess)
    verify(exactly = 0) { user.updateProfile(any()) }
    verify(exactly = 0) { user.verifyBeforeUpdateEmail(any()) }
  }

  @Test
  fun validationAndMissingSessionRejectBeforeSdkMutation() = runTest {
    assertTrue(
        repository.updateProfile(" \t ", "alice@example.test").exceptionOrNull()
            is AuthException.InvalidName
    )
    for (email in
        listOf(
            "",
            "not-email",
            "a@@example.test",
            "a b@example.test",
            "a@example..test",
            "a@example.test.",
            ".a@example.test",
        )) {
      assertTrue(
          repository.updateProfile("Alice", email).exceptionOrNull() is AuthException.InvalidEmail
      )
    }
    current = null
    assertTrue(
        repository.updateProfile("Alice", "alice@example.test").exceptionOrNull()
            is AuthException.SessionChanged
    )
    verify(exactly = 0) { user.updateProfile(any()) }
    verify(exactly = 0) { user.verifyBeforeUpdateEmail(any()) }
  }

  @Test
  fun recentLoginFailureIsExplicitAndKeepsConfirmedEmail() = runTest {
    every { user.verifyBeforeUpdateEmail(any()) } returns
        Tasks.forException(
            FirebaseAuthRecentLoginRequiredException(
                "ERROR_REQUIRES_RECENT_LOGIN",
                "recent login needed",
            )
        )
    assertTrue(
        repository.updateProfile("Alice Martin", "new@example.test").exceptionOrNull()
            is AuthException.RequiresRecentLogin
    )
    assertEquals("alice@example.test", repository.currentUser?.email)
  }

  @Test
  fun networkAndCollisionErrorsHavePublicCategories() = runTest {
    every { user.verifyBeforeUpdateEmail(any()) } returns
        Tasks.forException(FirebaseNetworkException("offline"))
    assertTrue(
        repository.updateProfile("Alice Martin", "new@example.test").exceptionOrNull()
            is AuthException.Network
    )
    every { user.verifyBeforeUpdateEmail(any()) } returns
        Tasks.forException(
            FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "taken")
        )
    assertTrue(
        repository.updateProfile("Alice Martin", "new@example.test").exceptionOrNull()
            is AuthException.EmailAlreadyInUse
    )
  }

  @Test
  fun profileFailureDoesNotRequestEmailChange() = runTest {
    every { user.updateProfile(any()) } returns
        Tasks.forException(FirebaseNetworkException("offline"))
    assertTrue(
        repository.updateProfile("Alice Dupont", "new@example.test").exceptionOrNull()
            is AuthException.Network
    )
    verify(exactly = 0) { user.verifyBeforeUpdateEmail(any()) }
    assertEquals("Alice Martin", repository.currentUser?.displayName)
  }

  @Test
  fun profileUpdateEmitsWithoutAnAuthStateCallback() = runTest {
    val emissions = mutableListOf<AuthUser?>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      repository.observeAuthState().collect { emissions.add(it) }
    }
    repository.updateProfile("Alice Dupont", "alice@example.test").getOrThrow()
    runCurrent()
    assertEquals(listOf("Alice Martin", "Alice Dupont"), emissions.map { it?.displayName })
  }

  @Test
  fun completedNameRemainsConfirmedWhenSeparateEmailRequestFails() = runTest {
    every { user.verifyBeforeUpdateEmail(any()) } returns
        Tasks.forException(FirebaseNetworkException("offline"))
    assertTrue(repository.updateProfile("Alice Dupont", "new@example.test").isFailure)
    assertEquals("Alice Dupont", repository.currentUser?.displayName)
    assertEquals("alice@example.test", repository.currentUser?.email)
  }

  @Test
  fun accountChangeDuringPendingNameTaskStopsFurtherWrites() = runTest {
    val task = TaskCompletionSource<Void>()
    every { user.updateProfile(any()) } returns task.task
    var result: Result<ProfileUpdateResult>? = null
    val job =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          result = repository.updateProfile("Alice Dupont", "new@example.test")
        }
    val bob = mockk<FirebaseUser>()
    every { bob.uid } returns "bob"
    current = bob
    task.setResult(null)
    runCurrent()
    assertTrue(result?.exceptionOrNull() is AuthException.SessionChanged)
    verify(exactly = 0) { user.verifyBeforeUpdateEmail(any()) }
    assertTrue(job.isCompleted)
  }

  @Test
  fun cancelledCallerDoesNotStartTasksEvenWithAtomicCoroutineStart() = runTest {
    val job =
        launch(start = CoroutineStart.ATOMIC) {
          repository.updateProfile("Alice Dupont", "new@example.test")
        }
    job.cancel()
    runCurrent()
    assertTrue(job.isCancelled)
    verify(exactly = 0) { user.updateProfile(any()) }
  }

  @Test
  fun cancellingPendingTaskRethrowsWithoutEmailWriteOrResult() = runTest {
    every { user.updateProfile(any()) } returns TaskCompletionSource<Void>().task
    var result: Result<ProfileUpdateResult>? = null
    val job =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          result = repository.updateProfile("Alice Dupont", "new@example.test")
        }
    job.cancel()
    runCurrent()
    assertNull(result)
    assertTrue(job.isCancelled)
    verify(exactly = 0) { user.verifyBeforeUpdateEmail(any()) }
  }

  @Test
  fun providerCancellationAndSynchronousExceptionsAreFailuresWhileCallerActive() = runTest {
    every { user.updateProfile(any()) } returns Tasks.forCanceled()
    assertTrue(
        repository.updateProfile("Alice Dupont", "alice@example.test").exceptionOrNull()
            is AuthException.Unknown
    )
    every { user.updateProfile(any()) } throws IllegalStateException("provider detail")
    assertTrue(
        repository.updateProfile("Alice Dupont", "alice@example.test").exceptionOrNull()
            is AuthException.Unknown
    )
  }
}
