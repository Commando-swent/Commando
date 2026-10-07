package com.android.sample.emulator.auth

// AI assistance: Claude (Anthropic).
import com.android.sample.emulator.auth.FirebaseAuthEmulator.runTest
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepositoryFirebase
import com.android.sample.model.authentication.AuthUser
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Email/password and session integration tests of [AuthRepositoryFirebase] against the local
 * Firebase Auth emulator.
 *
 * Run with `bash scripts/ci/run-firebase-emulator-tests.sh`, which starts the emulator and sets
 * `FIREBASE_AUTH_EMULATOR_HOST`. The tests use the `demo-commando` project, so they never touch the
 * production Firebase project. Every account is deleted before each test.
 *
 * Session restoration across a process restart is out of scope: it needs a real process relaunch,
 * which an instrumented test cannot do.
 */
@RunWith(RobolectricTestRunner::class)
class AuthRepositoryFirebaseEmulatorTest {

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
    if (::repository.isInitialized) repository.signOut()
  }

  @Test
  fun signUpWithEmail_returnsUserAndUpdatesCurrentUser() = runTest {
    val user = repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()

    assertTrue("uid should not be empty", user.uid.isNotEmpty())
    assertEquals(EMAIL, user.email)
    assertEquals(user, repository.currentUser)
  }

  @Test
  fun signUpWithEmail_trimsEmail() = runTest {
    val user = repository.signUpWithEmail("  $EMAIL  ", PASSWORD).getOrThrow()

    assertEquals(EMAIL, user.email)
  }

  @Test
  fun signUpWithEmail_twiceWithSameEmail_failsWithEmailAlreadyInUse() = runTest {
    repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    repository.signOut().getOrThrow()

    val error = repository.signUpWithEmail(EMAIL, PASSWORD).exceptionOrNull()

    assertTrue(
        "Expected EmailAlreadyInUse, got ${describe(error)}",
        error is AuthException.EmailAlreadyInUse,
    )
  }

  @Test
  fun signUpWithEmail_malformedEmail_failsWithInvalidEmail() = runTest {
    val error = repository.signUpWithEmail("not-an-email", PASSWORD).exceptionOrNull()

    assertTrue("Expected InvalidEmail, got ${describe(error)}", error is AuthException.InvalidEmail)
    assertNull(repository.currentUser)
  }

  @Test
  fun signInWithEmail_wrongPassword_failsWithInvalidCredentials() = runTest {
    repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    repository.signOut().getOrThrow()

    val error = repository.signInWithEmail(EMAIL, "wrong-password-1").exceptionOrNull()

    assertTrue(
        "Expected InvalidCredentials, got ${describe(error)}",
        error is AuthException.InvalidCredentials,
    )
    assertNull(repository.currentUser)
  }

  @Test
  fun signInWithEmail_unknownAccount_failsWithInvalidCredentials() = runTest {
    val error = repository.signInWithEmail(EMAIL, PASSWORD).exceptionOrNull()

    assertTrue(
        "Expected InvalidCredentials, got ${describe(error)}",
        error is AuthException.InvalidCredentials,
    )
    assertNull(repository.currentUser)
  }

  @Test
  fun signInWithEmail_malformedEmail_failsWithInvalidEmail() = runTest {
    val error = repository.signInWithEmail("not-an-email", PASSWORD).exceptionOrNull()

    assertTrue("Expected InvalidEmail, got ${describe(error)}", error is AuthException.InvalidEmail)
    assertNull(repository.currentUser)
  }

  @Test
  fun signInWithEmail_afterSignUp_returnsSameUser() = runTest {
    val signedUp = repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    repository.signOut().getOrThrow()

    val signedIn = repository.signInWithEmail(EMAIL, PASSWORD).getOrThrow()

    assertEquals(signedUp.uid, signedIn.uid)
    assertEquals(EMAIL, signedIn.email)
    assertEquals(signedIn, repository.currentUser)
  }

  @Test
  fun signOut_clearsCurrentUserAndAuthState() = runTest {
    repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()

    assertTrue(repository.signOut().isSuccess)

    assertNull(repository.currentUser)
    assertNull(withTimeout(TIMEOUT_MS) { repository.observeAuthState().first() })
  }

  @Test
  fun observeAuthState_emitsNullThenSignedInUser() = runTest {
    repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    repository.signOut().getOrThrow()

    val emissions = Collections.synchronizedList(mutableListOf<AuthUser?>())
    val firstEmission = CompletableDeferred<Unit>()
    val signedInEmission =
        async(Dispatchers.Default) {
          withTimeout(TIMEOUT_MS) {
            repository
                .observeAuthState()
                .onEach {
                  emissions.add(it)
                  firstEmission.complete(Unit)
                }
                .first { it != null }
          }
        }
    withTimeout(TIMEOUT_MS) { firstEmission.await() }

    val user = repository.signInWithEmail(EMAIL, PASSWORD).getOrThrow()
    val observed = signedInEmission.await()

    assertNull("First emission should be the signed-out state", emissions.first())
    assertEquals(user.uid, observed?.uid)
    assertEquals(EMAIL, observed?.email)
  }

  @Test
  fun observeAuthState_emitsNullThenUserThenNull() = runTest {
    repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    repository.signOut().getOrThrow()
    val emissions = Collections.synchronizedList(mutableListOf<AuthUser?>())
    val job =
        launch(Dispatchers.Default) { repository.observeAuthState().collect { emissions.add(it) } }

    awaitSize(emissions, 1)
    val user = repository.signInWithEmail(EMAIL, PASSWORD).getOrThrow()
    awaitSize(emissions, 2)
    repository.signOut().getOrThrow()
    awaitSize(emissions, 3)
    job.cancel()

    assertEquals(listOf(null, user, null), emissions.toList())
  }

  @Test
  fun observeAuthState_twoSimultaneousCollectorsBothSeeSignIn() = runTest {
    val first = Collections.synchronizedList(mutableListOf<AuthUser?>())
    val second = Collections.synchronizedList(mutableListOf<AuthUser?>())
    val jobs =
        listOf(first, second).map { list ->
          launch(Dispatchers.Default) { repository.observeAuthState().collect { list.add(it) } }
        }
    awaitSize(first, 1)
    awaitSize(second, 1)

    val user = repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    awaitSize(first, 2)
    awaitSize(second, 2)
    jobs.forEach { it.cancel() }

    assertEquals(listOf(null, user), first.toList())
    assertEquals(listOf(null, user), second.toList())
  }

  @Test
  fun signInWithEmail_uppercaseEmail_returnsSameUser() = runTest {
    val signedUp = repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    repository.signOut().getOrThrow()

    val signedIn = repository.signInWithEmail(EMAIL.uppercase(), PASSWORD).getOrThrow()

    assertEquals(signedUp.uid, signedIn.uid)
    assertEquals(EMAIL, signedIn.email)
  }

  @Test
  fun signInWithEmail_passwordIsCaseSensitive() = runTest {
    repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    repository.signOut().getOrThrow()

    val error = repository.signInWithEmail(EMAIL, PASSWORD.uppercase()).exceptionOrNull()

    assertTrue(
        "Expected InvalidCredentials, got ${describe(error)}",
        error is AuthException.InvalidCredentials,
    )
    assertNull(repository.currentUser)
  }

  @Test
  fun emptyEmailOrPassword_failsWithUnknownWithoutSession() = runTest {
    // The SDK rejects empty arguments locally (IllegalArgumentException), before any request.
    val results =
        listOf(
            "sign-up empty email" to repository.signUpWithEmail("  ", PASSWORD),
            "sign-up empty password" to repository.signUpWithEmail(EMAIL, ""),
            "sign-in empty email" to repository.signInWithEmail("", PASSWORD),
            "sign-in empty password" to repository.signInWithEmail(EMAIL, ""),
        )

    for ((case, result) in results) {
      val error = result.exceptionOrNull()
      assertTrue("$case: got ${describe(error)}", error is AuthException.Unknown)
      assertTrue("$case: got ${describe(error)}", error?.cause is IllegalArgumentException)
    }
    assertNull(repository.currentUser)
  }

  @Test
  fun signUpWithEmail_weakPassword_failsWithUnknown() = runTest {
    // Team decision: a weak password is not an invalid credential; the UI validates length.
    val error = repository.signUpWithEmail(EMAIL, "12345").exceptionOrNull()

    assertTrue("Expected Unknown, got ${describe(error)}", error is AuthException.Unknown)
    assertTrue(error?.cause is FirebaseAuthWeakPasswordException)
    assertNull(repository.currentUser)
  }

  @Test
  fun signInWithEmail_disabledAccount_failsWithInvalidCredentials() = runTest {
    val user = repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    repository.signOut().getOrThrow()
    FirebaseAuthEmulator.disableUser(user.uid)

    val error = repository.signInWithEmail(EMAIL, PASSWORD).exceptionOrNull()

    assertTrue(
        "Expected InvalidCredentials, got ${describe(error)}",
        error is AuthException.InvalidCredentials,
    )
    assertNull(repository.currentUser)
  }

  @Test
  fun signUpWithEmail_profileFieldsAreNull() = runTest {
    val user = repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()

    assertNull(user.displayName)
    assertNull(user.photoUrl)
  }

  @Test
  fun currentUser_matchesFirebaseCurrentUser() = runTest {
    assertNull(auth.currentUser)
    assertNull(repository.currentUser)

    val user = repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    val firebaseUser = auth.currentUser

    assertEquals(firebaseUser?.uid, user.uid)
    assertEquals(firebaseUser?.email, repository.currentUser?.email)
    repository.signOut().getOrThrow()
    assertNull(auth.currentUser)
    assertNull(repository.currentUser)
  }

  private suspend fun awaitSize(list: List<*>, size: Int) =
      withTimeout(TIMEOUT_MS) { while (list.size < size) delay(POLL_MS) }

  /** Type of the error and of its cause only; messages could contain sensitive data. */
  private fun describe(error: Throwable?): String =
      when (error) {
        null -> "no error"
        else -> "${error.javaClass.simpleName} (cause: ${error.cause?.javaClass?.simpleName})"
      }

  private companion object {
    const val EMAIL = "alice@example.test"
    const val PASSWORD = "example-password-1"
    const val TIMEOUT_MS = 10_000L
    const val POLL_MS = 20L
  }
}
