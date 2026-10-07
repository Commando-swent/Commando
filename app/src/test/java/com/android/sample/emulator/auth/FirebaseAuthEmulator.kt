package com.android.sample.emulator.auth

// AI assistance: Claude (Anthropic).
import android.content.Context
import android.os.Looper
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

  /** Disables the account [uid] with the emulator's admin API. */
  fun disableUser(uid: String) {
    val connection =
        URL("http://$host/identitytoolkit.googleapis.com/v1/projects/$PROJECT_ID/accounts:update")
            .openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.connectTimeout = TIMEOUT_MS
      connection.readTimeout = TIMEOUT_MS
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json")
      // The emulator accepts this fixed admin token; it is not a secret.
      connection.setRequestProperty("Authorization", "Bearer owner")
      val body = JSONObject().put("localId", uid).put("disableUser", true)
      connection.outputStream.use { it.write(body.toString().toByteArray()) }
      // Only the status code is reported: the response may contain tokens.
      val code = connection.responseCode
      check(code in 200..299) { "Auth emulator request failed (HTTP $code)." }
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
