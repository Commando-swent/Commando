package com.android.sample.model.authentication

// AI assistance: Claude (Anthropic).
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for email/password and session in [AuthRepositoryFirebase] with a mocked
 * [FirebaseAuth].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthRepositoryFirebaseTest {
  private val alice =
      AuthUser("alice", "Alice Example", "alice@example.test", "https://example.test/alice.png")
  private val bob =
      AuthUser("bob", "Bob Example", "bob@example.test", "https://example.test/bob.png")

  private val auth = mockk<FirebaseAuth>()
  private val repository = AuthRepositoryFirebase(auth)

  private enum class Operation {
    SIGN_UP,
    EMAIL;

    fun stub(auth: FirebaseAuth, task: Task<AuthResult>) {
      when (this) {
        SIGN_UP -> every { auth.createUserWithEmailAndPassword(any(), any()) } returns task
        EMAIL -> every { auth.signInWithEmailAndPassword(any(), any()) } returns task
      }
    }

    suspend fun invoke(repository: AuthRepository): Result<AuthUser> =
        when (this) {
          SIGN_UP -> repository.signUpWithEmail("alice@example.test", "example-password")
          EMAIL -> repository.signInWithEmail("alice@example.test", "example-password")
        }
  }

  private fun firebaseUser(user: AuthUser): FirebaseUser {
    val firebaseUser = mockk<FirebaseUser>()
    every { firebaseUser.uid } returns user.uid
    every { firebaseUser.displayName } returns user.displayName
    every { firebaseUser.email } returns user.email
    every { firebaseUser.photoUrl } returns user.photoUrl?.let { Uri.parse(it) }
    return firebaseUser
  }

  private fun authResult(user: FirebaseUser?): Task<AuthResult> {
    val result = mockk<AuthResult>()
    every { result.user } returns user
    return Tasks.forResult(result)
  }

  private fun assertFailure(
      operation: Operation,
      error: Exception,
      expected: KClass<out AuthException>,
  ) = runTest {
    operation.stub(auth, Tasks.forException(error))

    val returned = operation.invoke(repository).exceptionOrNull()

    assertTrue(
        "$operation with $error must map to ${expected.simpleName} but was $returned",
        expected.isInstance(returned),
    )
    assertSame(error, returned?.cause)
  }

  private fun assertFailureForAll(error: Exception, expected: KClass<out AuthException>) =
      Operation.entries.forEach { assertFailure(it, error, expected) }

  // ---------- success ----------

  @Test
  fun eachOperationReturnsConvertedUser() = runTest {
    for (operation in Operation.entries) {
      operation.stub(auth, authResult(firebaseUser(alice)))
      assertEquals(Result.success(alice), operation.invoke(repository))
    }
  }

  @Test
  fun eachOperationKeepsNullProfileFields() = runTest {
    val minimal = AuthUser("minimal")
    for (operation in Operation.entries) {
      operation.stub(auth, authResult(firebaseUser(minimal)))
      val user = operation.invoke(repository).getOrThrow()
      assertEquals("minimal", user.uid)
      assertNull(user.displayName)
      assertNull(user.email)
      assertNull(user.photoUrl)
    }
  }

  @Test
  fun signUpTrimsEmailAndKeepsPassword() = runTest {
    Operation.SIGN_UP.stub(auth, authResult(firebaseUser(alice)))

    repository.signUpWithEmail("  alice@example.test ", " pass word ")

    verify(exactly = 1) { auth.createUserWithEmailAndPassword("alice@example.test", " pass word ") }
  }

  @Test
  fun signInTrimsEmailAndKeepsPassword() = runTest {
    Operation.EMAIL.stub(auth, authResult(firebaseUser(alice)))

    repository.signInWithEmail("  alice@example.test ", " pass word ")

    verify(exactly = 1) { auth.signInWithEmailAndPassword("alice@example.test", " pass word ") }
  }

  // ---------- null user ----------

  @Test
  fun nullUserIsUnknownForEachOperation() = runTest {
    for (operation in Operation.entries) {
      operation.stub(auth, authResult(null))
      val returned = operation.invoke(repository).exceptionOrNull()
      assertTrue("$operation returned $returned", returned is AuthException.Unknown)
    }
  }

  // ---------- error mapping ----------

  @Test
  fun networkExceptionMapsToNetwork() =
      assertFailureForAll(FirebaseNetworkException("offline"), AuthException.Network::class)

  @Test
  fun tooManyRequestsMapsToTooManyRequests() =
      assertFailureForAll(
          FirebaseTooManyRequestsException("slow down"),
          AuthException.TooManyRequests::class,
      )

  @Test
  fun weakPasswordMapsToUnknownNotInvalidCredentials() =
      assertFailureForAll(
          FirebaseAuthWeakPasswordException("ERROR_WEAK_PASSWORD", "weak", "too short"),
          AuthException.Unknown::class,
      )

  @Test
  fun emailAlreadyInUseDependsOnOperation() {
    fun error() = FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "in use")
    assertFailure(Operation.SIGN_UP, error(), AuthException.EmailAlreadyInUse::class)
    assertFailure(Operation.EMAIL, error(), AuthException.AccountConflict::class)
  }

  @Test
  fun otherCollisionsMapToAccountConflict() {
    for (code in
        listOf(
            "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL",
            "ERROR_CREDENTIAL_ALREADY_IN_USE",
            "ERROR_SOMETHING_ELSE",
        )) {
      assertFailureForAll(
          FirebaseAuthUserCollisionException(code, "collision"),
          AuthException.AccountConflict::class,
      )
    }
  }

  @Test
  fun invalidEmailMapsToInvalidEmailForEveryOperation() =
      assertFailureForAll(
          FirebaseAuthInvalidCredentialsException("ERROR_INVALID_EMAIL", "bad email"),
          AuthException.InvalidEmail::class,
      )

  @Test
  fun otherInvalidCredentialsMapToInvalidCredentials() {
    for (code in listOf("ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL")) {
      assertFailureForAll(
          FirebaseAuthInvalidCredentialsException(code, "invalid"),
          AuthException.InvalidCredentials::class,
      )
    }
  }

  @Test
  fun invalidUserMapsToInvalidCredentials() {
    for (code in
        listOf("ERROR_USER_NOT_FOUND", "ERROR_USER_DISABLED", "ERROR_USER_TOKEN_EXPIRED")) {
      assertFailureForAll(
          FirebaseAuthInvalidUserException(code, "invalid user"),
          AuthException.InvalidCredentials::class,
      )
    }
  }

  @Test
  fun genericFirebaseErrorsMapToUnknown() {
    assertFailureForAll(
        FirebaseAuthException("ERROR_INTERNAL_ERROR", "internal"),
        AuthException.Unknown::class,
    )
    assertFailureForAll(FirebaseException("firebase"), AuthException.Unknown::class)
  }

  @Test
  fun emptyEmailRejectedSynchronouslyBySdkMapsToUnknown() = runTest {
    // The Firebase SDK rejects empty arguments by throwing before returning a Task.
    val error = IllegalArgumentException("Given String is empty or null")
    every { auth.signInWithEmailAndPassword(any(), any()) } throws error

    val returned = repository.signInWithEmail("   ", "example-password").exceptionOrNull()

    assertTrue("returned $returned", returned is AuthException.Unknown)
    assertSame(error, returned?.cause)
    verify(exactly = 1) { auth.signInWithEmailAndPassword("", "example-password") }
  }

  @Test
  fun unexpectedExceptionMapsToUnknown() =
      assertFailureForAll(IllegalStateException("unexpected"), AuthException.Unknown::class)

  // ---------- cancellation ----------

  @Test
  fun taskCancelledByFirebaseIsUnknownWhileCallerIsActive() = runTest {
    for (operation in Operation.entries) {
      val cancellation = CancellationException("cancelled by Firebase")
      operation.stub(auth, Tasks.forException(cancellation))
      val returned = operation.invoke(repository).exceptionOrNull()
      assertTrue("$operation returned $returned", returned is AuthException.Unknown)
      assertSame(cancellation, returned?.cause)

      operation.stub(auth, Tasks.forCanceled())
      val canceled = operation.invoke(repository).exceptionOrNull()
      assertTrue("$operation returned $canceled", canceled is AuthException.Unknown)
      assertTrue(canceled?.cause is CancellationException)
    }
  }

  @Test
  fun cancellationOfCallerIsRethrownForEachOperation() = runTest {
    for (operation in Operation.entries) {
      operation.stub(auth, TaskCompletionSource<AuthResult>().task)
      var result: Result<AuthUser>? = null
      val job =
          launch(UnconfinedTestDispatcher(testScheduler)) { result = operation.invoke(repository) }
      assertTrue("$operation must be suspended on the pending task", job.isActive)

      job.cancel()
      advanceUntilIdle()

      assertTrue("$operation job must end cancelled", job.isCancelled)
      assertNull("$operation must not produce a Result", result)
    }
  }

  // ---------- sign out ----------

  @Test
  fun signOutCallsFirebase() {
    every { auth.signOut() } just Runs

    assertEquals(Result.success(Unit), repository.signOut())

    verify(exactly = 1) { auth.signOut() }
  }

  @Test
  fun signOutFailureMapsToUnknownWithCause() {
    val error = IllegalStateException("sign out failed")
    every { auth.signOut() } throws error

    val returned = repository.signOut().exceptionOrNull()

    assertTrue("returned $returned", returned is AuthException.Unknown)
    assertSame(error, returned?.cause)
  }

  // ---------- default dependencies ----------

  @Test
  fun defaultConstructorUsesFirebaseAuthInstance() = runTest {
    mockkStatic(FirebaseAuth::class)
    try {
      every { FirebaseAuth.getInstance() } returns auth
      Operation.EMAIL.stub(auth, authResult(firebaseUser(alice)))

      val user = AuthRepositoryFirebase().signInWithEmail("alice@example.test", "example-password")

      assertEquals(Result.success(alice), user)
      verify(exactly = 1) { auth.signInWithEmailAndPassword(any(), any()) }
    } finally {
      unmockkStatic(FirebaseAuth::class)
    }
  }

  // ---------- current user ----------

  @Test
  fun currentUserIsConverted() {
    every { auth.currentUser } returns firebaseUser(alice)
    assertEquals(alice, repository.currentUser)
  }

  @Test
  fun currentUserIsNullWhenSignedOut() {
    every { auth.currentUser } returns null
    assertNull(repository.currentUser)
  }

  // ---------- observeAuthState ----------

  @Test
  fun observeAuthStateEmitsChangesWithoutDuplicatesAndRemovesListener() = runTest {
    val listener = slot<FirebaseAuth.AuthStateListener>()
    every { auth.addAuthStateListener(capture(listener)) } just Runs
    every { auth.removeAuthStateListener(any()) } just Runs
    every { auth.currentUser } returns null
    val emissions = mutableListOf<AuthUser?>()
    val job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeAuthState().collect { emissions.add(it) }
        }
    verify(exactly = 1) { auth.addAuthStateListener(any()) }
    assertEquals(listOf<AuthUser?>(null), emissions)

    fun change(user: AuthUser?) {
      every { auth.currentUser } returns user?.let { firebaseUser(it) }
      listener.captured.onAuthStateChanged(auth)
    }

    change(null)
    assertEquals(listOf<AuthUser?>(null), emissions)
    change(alice)
    assertEquals(listOf(null, alice), emissions)
    change(alice.copy())
    assertEquals(listOf(null, alice), emissions)
    change(bob)
    change(null)
    change(null)
    assertEquals(listOf(null, alice, bob, null), emissions)
    verify(exactly = 0) { auth.removeAuthStateListener(any()) }

    job.cancel()
    advanceUntilIdle()

    verify(exactly = 1) { auth.removeAuthStateListener(listener.captured) }
  }

  @Test
  fun observeAuthStateEmitsInitialSignedInUserOnce() = runTest {
    val listener = slot<FirebaseAuth.AuthStateListener>()
    every { auth.addAuthStateListener(capture(listener)) } just Runs
    every { auth.removeAuthStateListener(any()) } just Runs
    every { auth.currentUser } returns firebaseUser(alice)
    val emissions = mutableListOf<AuthUser?>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      repository.observeAuthState().collect { emissions.add(it) }
    }

    assertEquals(listOf<AuthUser?>(alice), emissions)
    listener.captured.onAuthStateChanged(auth)
    assertEquals(listOf<AuthUser?>(alice), emissions)
  }

  @Test
  fun eachCollectorRegistersAndRemovesItsOwnListener() = runTest {
    val listeners = mutableListOf<FirebaseAuth.AuthStateListener>()
    every { auth.addAuthStateListener(capture(listeners)) } just Runs
    every { auth.removeAuthStateListener(any()) } just Runs
    every { auth.currentUser } returns null
    val first = mutableListOf<AuthUser?>()
    val second = mutableListOf<AuthUser?>()
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val firstJob = launch(dispatcher) { repository.observeAuthState().collect { first.add(it) } }
    val secondJob = launch(dispatcher) { repository.observeAuthState().collect { second.add(it) } }

    assertEquals(2, listeners.size)
    assertNotSame(listeners[0], listeners[1])
    every { auth.currentUser } returns firebaseUser(alice)
    listeners.forEach { it.onAuthStateChanged(auth) }
    assertEquals(listOf(null, alice), first)
    assertEquals(listOf(null, alice), second)

    firstJob.cancel()
    advanceUntilIdle()
    verify(exactly = 1) { auth.removeAuthStateListener(listeners[0]) }
    verify(exactly = 0) { auth.removeAuthStateListener(listeners[1]) }

    every { auth.currentUser } returns null
    listeners[1].onAuthStateChanged(auth)
    assertEquals(listOf(null, alice), first)
    assertEquals(listOf(null, alice, null), second)

    secondJob.cancel()
    advanceUntilIdle()
    verify(exactly = 1) { auth.removeAuthStateListener(listeners[1]) }
  }
}
