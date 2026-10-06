package com.android.sample.utils

import android.util.Base64
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Connects instrumented tests to the local Firebase Auth emulator.
 *
 * Tests must call [connect] first: it fails with a clear message when the emulator is not running,
 * so tests never fall back to the production Firebase project. Tokens and passwords are never
 * logged.
 */
object FirebaseAuthEmulator {
  /** Host machine address as seen from the Android emulator. */
  const val HOST = "10.0.2.2"
  const val AUTH_PORT = 9099
  private const val TIMEOUT_MS = 2_000

  private val projectId: String by lazy {
    requireNotNull(FirebaseApp.getInstance().options.projectId) { "Missing Firebase project ID." }
  }

  private val connection: Unit by lazy {
    check(isReachable()) {
      "Firebase Auth emulator is not reachable at $HOST:$AUTH_PORT. " +
          "Start it with: firebase emulators:start --only auth"
    }
    Firebase.auth.useEmulator(HOST, AUTH_PORT)
  }

  /** Points [Firebase.auth] at the emulator once, or fails if the emulator is not running. */
  fun connect(): FirebaseAuth {
    connection
    return Firebase.auth
  }

  /** Deletes every account in the emulator for the current project. */
  fun clearAccounts() {
    val code = request("DELETE", "/emulator/v1/projects/$projectId/accounts")
    check(code in 200..299) { "Failed to clear Auth emulator accounts (HTTP $code)." }
  }

  /** Builds an unsigned Google ID token that the Auth emulator accepts. */
  fun fakeGoogleIdToken(sub: String, email: String, name: String): String {
    fun encode(json: JSONObject): String =
        Base64.encodeToString(
            json.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )

    val header = JSONObject(mapOf("alg" to "none"))
    val payload =
        JSONObject(mapOf("sub" to sub, "email" to email, "email_verified" to true, "name" to name))
    return "${encode(header)}.${encode(payload)}.sig"
  }

  /**
   * Creates (or signs in) a Google account directly through the emulator REST API, as in the
   * bootcamp's `FirebaseEmulator.createGoogleUser`, and returns its uid.
   */
  fun createGoogleUser(idToken: String): String {
    val body =
        JSONObject()
            .put("postBody", "id_token=$idToken&providerId=google.com")
            .put("requestUri", "http://localhost")
            .put("returnSecureToken", true)
    return postJson(
            "/identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=fake-api-key",
            body,
        )
        .getString("localId")
  }

  /** Disables the account [uid] with the emulator's admin API. */
  fun disableUser(uid: String) {
    val body = JSONObject().put("localId", uid).put("disableUser", true)
    postJson("/identitytoolkit.googleapis.com/v1/projects/$projectId/accounts:update", body)
  }

  private fun postJson(path: String, body: JSONObject): JSONObject {
    val connection = URL("http://$HOST:$AUTH_PORT$path").openConnection() as HttpURLConnection
    return try {
      connection.requestMethod = "POST"
      connection.connectTimeout = TIMEOUT_MS
      connection.readTimeout = TIMEOUT_MS
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json")
      // The emulator accepts this fixed admin token; it is not a secret.
      connection.setRequestProperty("Authorization", "Bearer owner")
      connection.outputStream.use { it.write(body.toString().toByteArray()) }
      val code = connection.responseCode
      // Only the status code is reported: the response may contain tokens.
      check(code in 200..299) { "Auth emulator request failed (HTTP $code)." }
      JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } finally {
      connection.disconnect()
    }
  }

  private fun isReachable(): Boolean =
      try {
        request("GET", "/") in 200..499
      } catch (_: Exception) {
        false
      }

  private fun request(method: String, path: String): Int {
    val connection = URL("http://$HOST:$AUTH_PORT$path").openConnection() as HttpURLConnection
    return try {
      connection.requestMethod = method
      connection.connectTimeout = TIMEOUT_MS
      connection.readTimeout = TIMEOUT_MS
      connection.responseCode
    } finally {
      connection.disconnect()
    }
  }
}
