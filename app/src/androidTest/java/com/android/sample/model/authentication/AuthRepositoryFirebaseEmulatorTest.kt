package com.android.sample.model.authentication

import android.os.Bundle
import android.util.Base64
import androidx.credentials.CustomCredential
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sample.utils.FirebaseAuthEmulator
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.auth
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests of [AuthRepositoryFirebase] against the local Firebase Auth emulator.
 *
 * Requires the emulator to be running on the host: `firebase emulators:start --only auth --project
 * command-o`. The tests fail immediately with a clear message when it is not reachable, and never
 * touch the production Firebase project. Every account is deleted before each test.
 *
 * Session restoration across a process restart is out of scope: it needs a real process relaunch,
 * which an instrumented test cannot do.
 */
@RunWith(AndroidJUnit4::class)
class AuthRepositoryFirebaseEmulatorTest {

  private lateinit var repository: AuthRepositoryFirebase

  @Before
  fun setUp() {
    val auth = FirebaseAuthEmulator.connect()
    FirebaseAuthEmulator.clearAccounts()
    auth.signOut()
    repository = AuthRepositoryFirebase(auth)
  }

  @After
  fun tearDown() {
    if (::repository.isInitialized) repository.signOut()
  }

  @Test
  fun signUpWithEmail_returnsUserAndUpdatesCurrentUser() = runBlocking {
    val user = repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()

    assertTrue("uid should not be empty", user.uid.isNotEmpty())
    assertEquals(EMAIL, user.email)
    assertEquals(user, repository.currentUser)
  }

  @Test
  fun signUpWithEmail_trimsEmail() = runBlocking {
    val user = repository.signUpWithEmail("  $EMAIL  ", PASSWORD).getOrThrow()

    assertEquals(EMAIL, user.email)
  }

  @Test
  fun signUpWithEmail_twiceWithSameEmail_failsWithEmailAlreadyInUse() = runBlocking {
    repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    repository.signOut().getOrThrow()

    val error = repository.signUpWithEmail(EMAIL, PASSWORD).exceptionOrNull()

    assertTrue(
        "Expected EmailAlreadyInUse, got ${describe(error)}",
        error is AuthException.EmailAlreadyInUse,
    )
  }

  @Test
  fun signUpWithEmail_malformedEmail_failsWithInvalidEmail() = runBlocking {
    val error = repository.signUpWithEmail("not-an-email", PASSWORD).exceptionOrNull()

    assertTrue("Expected InvalidEmail, got ${describe(error)}", error is AuthException.InvalidEmail)
    assertNull(repository.currentUser)
  }

  @Test
  fun signInWithEmail_wrongPassword_failsWithInvalidCredentials() = runBlocking {
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
  fun signInWithEmail_unknownAccount_failsWithInvalidCredentials() = runBlocking {
    val error = repository.signInWithEmail(EMAIL, PASSWORD).exceptionOrNull()

    assertTrue(
        "Expected InvalidCredentials, got ${describe(error)}",
        error is AuthException.InvalidCredentials,
    )
    assertNull(repository.currentUser)
  }

  @Test
  fun signInWithEmail_malformedEmail_failsWithInvalidEmail() = runBlocking {
    val error = repository.signInWithEmail("not-an-email", PASSWORD).exceptionOrNull()

    assertTrue("Expected InvalidEmail, got ${describe(error)}", error is AuthException.InvalidEmail)
    assertNull(repository.currentUser)
  }

  @Test
  fun signInWithEmail_afterSignUp_returnsSameUser() = runBlocking {
    val signedUp = repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    repository.signOut().getOrThrow()

    val signedIn = repository.signInWithEmail(EMAIL, PASSWORD).getOrThrow()

    assertEquals(signedUp.uid, signedIn.uid)
    assertEquals(EMAIL, signedIn.email)
    assertEquals(signedIn, repository.currentUser)
  }

  @Test
  fun signOut_clearsCurrentUserAndAuthState() = runBlocking {
    repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()

    assertTrue(repository.signOut().isSuccess)

    assertNull(repository.currentUser)
    assertNull(withTimeout(TIMEOUT_MS) { repository.observeAuthState().first() })
  }

  @Test
  fun observeAuthState_emitsNullThenSignedInUser() = runBlocking {
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
  fun signInWithGoogle_withEmulatorToken_returnsUserWithEmail() = runBlocking {
    val user = repository.signInWithGoogle(googleCredential(GOOGLE_SUB, EMAIL, NAME)).getOrThrow()

    assertTrue("uid should not be empty", user.uid.isNotEmpty())
    assertEquals(EMAIL, user.email)
    // The emulator may or may not propagate the name claim; only check it when present.
    user.displayName?.let { assertEquals(NAME, it) }
    assertEquals(user, repository.currentUser)
  }

  @Test
  fun signInWithGoogle_sameVerifiedEmailAsEmailAccount_returnsSameUid() = runBlocking {
    val emailUser = repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    repository.signOut().getOrThrow()

    val googleUser =
        repository.signInWithGoogle(googleCredential(GOOGLE_SUB, EMAIL, NAME)).getOrThrow()

    // The emulator links the verified Google identity to the existing email account.
    assertEquals(emailUser.uid, googleUser.uid)
    assertEquals(EMAIL, googleUser.email)
    assertEquals(googleUser, repository.currentUser)
  }

  @Test
  fun signInWithGoogle_unverifiedEmailOfEmailAccount_failsWithAccountConflict() = runBlocking {
    repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    repository.signOut().getOrThrow()
    val token =
        unsignedJwt(
            JSONObject(
                mapOf(
                    "sub" to GOOGLE_SUB,
                    "email" to EMAIL,
                    "email_verified" to false,
                    "name" to NAME,
                )
            )
        )

    val error = repository.signInWithGoogle(googleCredential(token, EMAIL)).exceptionOrNull()

    assertTrue(
        "Expected AccountConflict, got ${describe(error)}",
        error is AuthException.AccountConflict,
    )
    assertNull(repository.currentUser)
  }

  @Test
  fun signUpWithEmail_afterGoogleAccountWithSameEmail_failsWithEmailAlreadyInUse() = runBlocking {
    repository.signInWithGoogle(googleCredential(GOOGLE_SUB, EMAIL, NAME)).getOrThrow()
    repository.signOut().getOrThrow()

    val error = repository.signUpWithEmail(EMAIL, PASSWORD).exceptionOrNull()

    assertTrue(
        "Expected EmailAlreadyInUse, got ${describe(error)}",
        error is AuthException.EmailAlreadyInUse,
    )
    assertNull(repository.currentUser)
  }

  @Test
  fun signInWithEmail_googleOnlyAccount_failsWithInvalidCredentials() = runBlocking {
    repository.signInWithGoogle(googleCredential(GOOGLE_SUB, EMAIL, NAME)).getOrThrow()
    repository.signOut().getOrThrow()

    val error = repository.signInWithEmail(EMAIL, PASSWORD).exceptionOrNull()

    assertTrue(
        "Expected InvalidCredentials, got ${describe(error)}",
        error is AuthException.InvalidCredentials,
    )
    assertNull(repository.currentUser)
  }

  @Test
  fun observeAuthState_emitsGoogleUserThenNullAfterSignOut() = runBlocking {
    val emissions = Collections.synchronizedList(mutableListOf<AuthUser?>())
    val job =
        launch(Dispatchers.Default) { repository.observeAuthState().collect { emissions.add(it) } }

    awaitSize(emissions, 1)
    val user = repository.signInWithGoogle(googleCredential(GOOGLE_SUB, EMAIL, NAME)).getOrThrow()
    awaitSize(emissions, 2)
    repository.signOut().getOrThrow()
    awaitSize(emissions, 3)
    job.cancel()

    assertEquals(listOf(null, user, null), emissions.toList())
  }

  @Test
  fun observeAuthState_emitsNullThenUserThenNull() = runBlocking {
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
  fun observeAuthState_twoSimultaneousCollectorsBothSeeSignIn() = runBlocking {
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
  fun signInWithEmail_uppercaseEmail_returnsSameUser() = runBlocking {
    val signedUp = repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    repository.signOut().getOrThrow()

    val signedIn = repository.signInWithEmail(EMAIL.uppercase(), PASSWORD).getOrThrow()

    assertEquals(signedUp.uid, signedIn.uid)
    assertEquals(EMAIL, signedIn.email)
  }

  @Test
  fun signInWithEmail_passwordIsCaseSensitive() = runBlocking {
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
  fun emptyEmailOrPassword_failsWithUnknownWithoutSession() = runBlocking {
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
  fun signUpWithEmail_weakPassword_failsWithUnknown() = runBlocking {
    // Team decision: a weak password is not an invalid credential; the UI validates length.
    val error = repository.signUpWithEmail(EMAIL, "12345").exceptionOrNull()

    assertTrue("Expected Unknown, got ${describe(error)}", error is AuthException.Unknown)
    assertTrue(error?.cause is FirebaseAuthWeakPasswordException)
    assertNull(repository.currentUser)
  }

  @Test
  fun signInWithEmail_disabledAccount_failsWithInvalidCredentials() = runBlocking {
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
  fun signUpWithEmail_profileFieldsAreNull() = runBlocking {
    val user = repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()

    assertNull(user.displayName)
    assertNull(user.photoUrl)
  }

  @Test
  fun currentUser_matchesFirebaseCurrentUser() = runBlocking {
    assertNull(Firebase.auth.currentUser)
    assertNull(repository.currentUser)

    val user = repository.signUpWithEmail(EMAIL, PASSWORD).getOrThrow()
    val firebaseUser = Firebase.auth.currentUser

    assertEquals(firebaseUser?.uid, user.uid)
    assertEquals(firebaseUser?.email, repository.currentUser?.email)
    repository.signOut().getOrThrow()
    assertNull(Firebase.auth.currentUser)
    assertNull(repository.currentUser)
  }

  @Test
  fun signInWithGoogle_twiceWithSameSub_returnsSameUid() = runBlocking {
    val first = repository.signInWithGoogle(googleCredential(GOOGLE_SUB, EMAIL, NAME)).getOrThrow()
    repository.signOut().getOrThrow()

    val second = repository.signInWithGoogle(googleCredential(GOOGLE_SUB, EMAIL, NAME)).getOrThrow()

    assertEquals(first.uid, second.uid)
  }

  @Test
  fun signInWithGoogle_differentSubs_returnDifferentUids() = runBlocking {
    val first = repository.signInWithGoogle(googleCredential(GOOGLE_SUB, EMAIL, NAME)).getOrThrow()
    repository.signOut().getOrThrow()

    val second =
        repository
            .signInWithGoogle(googleCredential("emulator-google-sub-2", OTHER_EMAIL, NAME))
            .getOrThrow()

    assertNotEquals(first.uid, second.uid)
  }

  @Test
  fun signInWithGoogle_preCreatedGoogleUser_returnsExistingUid() = runBlocking {
    val uid =
        FirebaseAuthEmulator.createGoogleUser(
            FirebaseAuthEmulator.fakeGoogleIdToken(GOOGLE_SUB, EMAIL, NAME)
        )

    val user = repository.signInWithGoogle(googleCredential(GOOGLE_SUB, EMAIL, NAME)).getOrThrow()

    assertEquals(uid, user.uid)
    assertEquals(EMAIL, user.email)
  }

  @Test
  fun signInWithGoogle_malformedToken_failsWithInvalidGoogleCredential() = runBlocking {
    val bundle = googleCredential(GOOGLE_SUB, EMAIL, NAME).data
    // The library's bundle keys are internal, so locate the ID token entry by its value.
    val idTokenKey = bundle.keySet().single { bundle.getString(it)?.count { c -> c == '.' } == 2 }
    bundle.putString(idTokenKey, "not-a-jwt")

    val error =
        repository
            .signInWithGoogle(
                CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, bundle)
            )
            .exceptionOrNull()

    assertTrue(
        "Expected InvalidGoogleCredential, got ${describe(error)}",
        error is AuthException.InvalidGoogleCredential,
    )
    assertNull(repository.currentUser)
  }

  @Test
  fun signInWithGoogle_tokenWithoutSub_failsWithInvalidGoogleCredential() = runBlocking {
    // The Google library refuses to build such a credential, so swap the token in the bundle.
    val bundle = googleCredential(GOOGLE_SUB, EMAIL, NAME).data
    val idTokenKey = bundle.keySet().single { bundle.getString(it)?.count { c -> c == '.' } == 2 }
    bundle.putString(idTokenKey, unsignedJwt(JSONObject(mapOf("email" to EMAIL))))

    val error =
        repository
            .signInWithGoogle(
                CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, bundle)
            )
            .exceptionOrNull()

    assertTrue(
        "Expected InvalidGoogleCredential, got ${describe(error)}",
        error is AuthException.InvalidGoogleCredential,
    )
    assertNull(repository.currentUser)
  }

  @Test
  fun signInWithGoogle_otherCustomCredentialType_failsWithInvalidGoogleCredential() = runBlocking {
    val data = googleCredential(GOOGLE_SUB, EMAIL, NAME).data

    val error =
        repository
            .signInWithGoogle(CustomCredential("com.example.test.OTHER", data))
            .exceptionOrNull()
    val empty = repository.signInWithGoogle(CustomCredential("com.example.test.OTHER", Bundle()))

    assertTrue("got ${describe(error)}", error is AuthException.InvalidGoogleCredential)
    assertTrue(empty.exceptionOrNull() is AuthException.InvalidGoogleCredential)
    assertNull(repository.currentUser)
  }

  private suspend fun awaitSize(list: List<*>, size: Int) =
      withTimeout(TIMEOUT_MS) { while (list.size < size) delay(POLL_MS) }

  private fun unsignedJwt(payload: JSONObject): String {
    fun encode(json: JSONObject): String =
        Base64.encodeToString(
            json.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
    return "${encode(JSONObject(mapOf("alg" to "none")))}.${encode(payload)}.sig"
  }

  private fun googleCredential(sub: String, email: String, name: String): CustomCredential =
      googleCredential(FirebaseAuthEmulator.fakeGoogleIdToken(sub, email, name), email)

  private fun googleCredential(idToken: String, email: String): CustomCredential {
    val googleCredential =
        GoogleIdTokenCredential.Builder().setId(email).setIdToken(idToken).build()
    return CustomCredential(
        GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        googleCredential.data,
    )
  }

  /** Type of the error and of its cause only; messages could contain sensitive data. */
  private fun describe(error: Throwable?): String =
      when (error) {
        null -> "no error"
        else -> "${error.javaClass.simpleName} (cause: ${error.cause?.javaClass?.simpleName})"
      }

  private companion object {
    const val EMAIL = "alice@example.test"
    const val PASSWORD = "example-password-1"
    const val GOOGLE_SUB = "emulator-google-sub-1"
    const val NAME = "Alice Example"
    const val OTHER_EMAIL = "bob@example.test"
    const val TIMEOUT_MS = 10_000L
    const val POLL_MS = 20L
  }
}
