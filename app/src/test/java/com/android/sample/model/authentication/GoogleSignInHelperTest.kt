package com.android.sample.model.authentication

// AI assistance: Claude (Anthropic).
import android.os.Bundle
import android.util.Base64
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.GoogleAuthCredential
import com.google.firebase.auth.GoogleAuthProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GoogleSignInHelperTest {
  private val helper: GoogleSignInHelper = DefaultGoogleSignInHelper()

  /** Unsigned JWT whose payload the library can parse; the signature is never verified. */
  private fun fakeIdToken(sub: String, email: String, name: String): String {
    fun encode(json: JSONObject): String =
        Base64.encodeToString(
            json.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )

    val header = JSONObject(mapOf("alg" to "none"))
    val payload = JSONObject(mapOf("sub" to sub, "email" to email, "name" to name))
    return "${encode(header)}.${encode(payload)}.sig"
  }

  @Test
  fun extractIdTokenCredentialReadsIdAndTokenFromValidBundle() {
    val id = "alice@example.test"
    val idToken = fakeIdToken(sub = "alice", email = id, name = "Alice Example")
    val bundle = GoogleIdTokenCredential.Builder().setId(id).setIdToken(idToken).build().data

    val credential = helper.extractIdTokenCredential(bundle)

    assertEquals(idToken, credential.idToken)
    assertEquals(id, credential.id)
  }

  @Test
  fun extractIdTokenCredentialRejectsEmptyBundle() {
    assertThrows(GoogleIdTokenParsingException::class.java) {
      helper.extractIdTokenCredential(Bundle())
    }
  }

  @Test
  fun extractIdTokenCredentialRejectsMalformedIdToken() {
    val id = "alice@example.test"
    val idToken = fakeIdToken(sub = "alice", email = id, name = "Alice Example")
    val bundle = GoogleIdTokenCredential.Builder().setId(id).setIdToken(idToken).build().data
    // The library's bundle keys are internal, so locate the ID token entry by its value.
    val idTokenKey = bundle.keySet().single { bundle.getString(it) == idToken }
    bundle.putString(idTokenKey, "not-a-jwt")

    assertThrows(GoogleIdTokenParsingException::class.java) {
      helper.extractIdTokenCredential(bundle)
    }
  }

  @Test
  fun toFirebaseCredentialCreatesGoogleCredential() {
    val credential = helper.toFirebaseCredential("token")

    assertTrue(credential is GoogleAuthCredential)
    assertEquals(GoogleAuthProvider.PROVIDER_ID, credential.provider)
  }
}
