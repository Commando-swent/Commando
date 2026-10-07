package com.android.sample.utils

// AI assistance: Claude (Anthropic).
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
          "Start it with: firebase emulators:start --only auth --project command-o"
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
