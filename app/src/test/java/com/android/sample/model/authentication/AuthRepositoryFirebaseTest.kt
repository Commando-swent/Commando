package com.android.sample.model.authentication

import android.net.Uri
import android.os.Bundle
import androidx.credentials.Credential
import androidx.credentials.CustomCredential
import androidx.credentials.PasswordCredential
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Unit tests for [AuthRepositoryFirebase] with a mocked [FirebaseAuth] and a fake helper. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthRepositoryFirebaseTest {
  private val alice =
      AuthUser("alice", "Alice Example", "alice@example.test", "https://example.test/alice.png")
  private val bob =
      AuthUser("bob", "Bob Example", "bob@example.test", "https://example.test/bob.png")

  private val auth = mockk<FirebaseAuth>()
  private val helper = FakeGoogleSignInHelper()
  private val repository = AuthRepositoryFirebase(auth, helper)

  /** Records calls and returns canned values; never inspects real tokens. */
  private class FakeGoogleSignInHelper : GoogleSignInHelper {
    val idToken = "id-token.example.test"
    val firebaseCredential = mockk<AuthCredential>()
    var extractError: Exception? = null
    val extractedBundles = mutableListOf<Bundle>()
    val convertedTokens = mutableListOf<String>()

    override fun extractIdTokenCredential(bundle: Bundle): GoogleIdTokenCredential {
      extractedBundles.add(bundle)
      extractError?.let { throw it }
      val credential = mockk<GoogleIdTokenCredential>()
      every { credential.idToken } returns idToken
      return credential
    }

    override fun toFirebaseCredential(idToken: String): AuthCredential {
      convertedTokens.add(idToken)
      return firebaseCredential
    }
  }

  private enum class Operation {
    SIGN_UP,
    EMAIL,
    GOOGLE;

    fun stub(auth: FirebaseAuth, task: Task<AuthResult>) {
      when (this) {
        SIGN_UP -> every { auth.createUserWithEmailAndPassword(any(), any()) } returns task
        EMAIL -> every { auth.signInWithEmailAndPassword(any(), any()) } returns task
        GOOGLE -> every { auth.signInWithCredential(any()) } returns task
      }
    }

    suspend fun invoke(repository: AuthRepository): Result<AuthUser> =
        when (this) {
          SIGN_UP -> repository.signUpWithEmail("alice@example.test", "example-password")
          EMAIL -> repository.signInWithEmail("alice@example.test", "example-password")
          GOOGLE -> repository.signInWithGoogle(googleCredential())
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

  @Test
  fun googleSignInPassesBundleAndTokenThroughHelper() = runTest {
    Operation.GOOGLE.stub(auth, authResult(firebaseUser(alice)))
    val credential = googleCredential()

    assertEquals(Result.success(alice), repository.signInWithGoogle(credential))

    assertSame(credential.data, helper.extractedBundles.single())
    assertEquals(listOf(helper.idToken), helper.convertedTokens)
    verify(exactly = 1) { auth.signInWithCredential(helper.firebaseCredential) }
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
  fun googleIdTokenParsingExceptionMapsToInvalidGoogleCredential() =
      assertFailureForAll(
          GoogleIdTokenParsingException(IllegalArgumentException("malformed")),
          AuthException.InvalidGoogleCredential::class,
      )

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
    assertFailure(Operation.GOOGLE, error(), AuthException.AccountConflict::class)
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
  fun otherInvalidCredentialsDependOnOperation() {
    for (code in listOf("ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL")) {
      fun error() = FirebaseAuthInvalidCredentialsException(code, "invalid")
      assertFailure(Operation.SIGN_UP, error(), AuthException.InvalidCredentials::class)
      assertFailure(Operation.EMAIL, error(), AuthException.InvalidCredentials::class)
      assertFailure(Operation.GOOGLE, error(), AuthException.InvalidGoogleCredential::class)
    }
  }

  @Test
  fun invalidUserMapsToInvalidCredentials() =
      assertFailureForAll(
          FirebaseAuthInvalidUserException("ERROR_USER_NOT_FOUND", "no user"),
          AuthException.InvalidCredentials::class,
      )

  @Test
  fun unexpectedExceptionMapsToUnknown() =
      assertFailureForAll(IllegalStateException("unexpected"), AuthException.Unknown::class)

  // ---------- Google credential handling ----------

  @Test
  fun nonGoogleCredentialsAreRejectedWithoutCallingAuthOrHelper() = runTest {
    val credentials: List<Credential> =
        listOf(
            PasswordCredential("alice@example.test", "example-password"),
            CustomCredential("com.example.test.OTHER", Bundle()),
            CustomCredential(
                GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL,
                Bundle(),
            ),
        )
    for (credential in credentials) {
      val returned = repository.signInWithGoogle(credential).exceptionOrNull()
      assertTrue(
          "$credential returned $returned",
          returned is AuthException.InvalidGoogleCredential,
      )
    }
    verify(exactly = 0) { auth.signInWithCredential(any()) }
    assertTrue(helper.extractedBundles.isEmpty())
    assertTrue(helper.convertedTokens.isEmpty())
  }

  @Test
  fun helperParsingFailureMapsToInvalidGoogleCredentialWithCause() = runTest {
    val error = GoogleIdTokenParsingException(IllegalArgumentException("malformed"))
    helper.extractError = error

    val returned = repository.signInWithGoogle(googleCredential()).exceptionOrNull()

    assertTrue("returned $returned", returned is AuthException.InvalidGoogleCredential)
    assertSame(error, returned?.cause)
    verify(exactly = 0) { auth.signInWithCredential(any()) }
  }

  @Test
  fun helperUnexpectedFailureMapsToUnknownWithCause() = runTest {
    val error = IllegalStateException("unexpected")
    helper.extractError = error

    val returned = repository.signInWithGoogle(googleCredential()).exceptionOrNull()

    assertTrue("returned $returned", returned is AuthException.Unknown)
    assertSame(error, returned?.cause)
  }

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
    verify(exactly = 0) { auth.signOut() }
  }

  @Test
  fun helperCancellationIsUnknownWhileCallerIsActive() = runTest {
    val cancellation = CancellationException("cancelled")
    helper.extractError = cancellation

    val returned = repository.signInWithGoogle(googleCredential()).exceptionOrNull()

    assertTrue("returned $returned", returned is AuthException.Unknown)
    assertSame(cancellation, returned?.cause)
    verify(exactly = 0) { auth.signInWithCredential(any()) }
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

  private companion object {
    fun googleCredential(): CustomCredential =
        CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, Bundle())
  }
}
