package com.android.sample.emulator.auth

// AI assistance: Claude (Anthropic).
import android.content.Context
import android.os.Looper
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf

/**
 * Connects Robolectric tests to the Firebase Auth emulator started by
 * `scripts/ci/run-firebase-emulator-tests.sh`, which sets `FIREBASE_AUTH_EMULATOR_HOST`.
 *
 * A dedicated [FirebaseApp] on the `demo-commando` project is used, so tests never reach the
 * production Firebase project. Tokens and passwords are never logged.
 */
object FirebaseAuthEmulator {
  private const val PROJECT_ID = "demo-commando"
  private const val APP_NAME = "auth-emulator"
  private const val TIMEOUT_MS = 2_000
  private const val TEST_TIMEOUT_MS = 30_000L

  private val host: String by lazy {
    checkNotNull(System.getenv("FIREBASE_AUTH_EMULATOR_HOST")) {
      "FIREBASE_AUTH_EMULATOR_HOST is not set. Run: bash scripts/ci/run-firebase-emulator-tests.sh"
    }
  }

  /** Returns a [FirebaseAuth] pointed at the emulator. */
  fun connect(): FirebaseAuth {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val app =
        FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId(PROJECT_ID)
                    .setApiKey("fake-api-key")
                    .setApplicationId("1:1:android:1")
                    .build(),
                APP_NAME,
            )
    val (hostName, port) = host.split(":")
    return FirebaseAuth.getInstance(app).apply { useEmulator(hostName, port.toInt()) }
  }

  /**
   * Runs [block] off the main thread while draining the Robolectric main looper, where Firebase
   * delivers its callbacks; blocking the main thread instead would deadlock.
   */
  fun runTest(block: suspend CoroutineScope.() -> Unit) {
    var failure: Throwable? = null
    var done = false
    val worker = Thread {
      try {
        runBlocking { withContext(Dispatchers.IO) { coroutineScope { block() } } }
      } catch (e: Throwable) {
        failure = e
      } finally {
        done = true
      }
    }
    worker.start()
    val deadline = System.currentTimeMillis() + TEST_TIMEOUT_MS
    while (!done) {
      shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(5)
      check(System.currentTimeMillis() < deadline) { "Emulator test timed out." }
    }
    failure?.let { throw it }
  }

  /** Deletes every account in the emulator. */
  fun clearAccounts() {
    val code = request("DELETE", "/emulator/v1/projects/$PROJECT_ID/accounts")
    check(code in 200..299) { "Failed to clear Auth emulator accounts (HTTP $code)." }
  }

  /** Builds an unsigned Google ID token that the Auth emulator accepts. */
  fun fakeGoogleIdToken(sub: String, email: String, name: String): String =
      unsignedJwt(
          JSONObject(
              mapOf("sub" to sub, "email" to email, "email_verified" to true, "name" to name)
          )
      )

  /** Builds an unsigned JWT with [payload]; the emulator does not check signatures. */
  fun unsignedJwt(payload: JSONObject): String {
    fun encode(json: JSONObject): String =
        Base64.encodeToString(
            json.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
    return "${encode(JSONObject(mapOf("alg" to "none")))}.${encode(payload)}.sig"
  }

  /** Creates (or signs in) a Google account through the emulator REST API and returns its uid. */
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
    postJson("/identitytoolkit.googleapis.com/v1/projects/$PROJECT_ID/accounts:update", body)
  }

  private fun postJson(path: String, body: JSONObject): JSONObject {
    val connection = URL("http://$host$path").openConnection() as HttpURLConnection
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

  private fun request(method: String, path: String): Int {
    val connection = URL("http://$host$path").openConnection() as HttpURLConnection
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
