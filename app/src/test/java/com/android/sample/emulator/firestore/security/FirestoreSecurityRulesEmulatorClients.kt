package com.android.sample.emulator.firestore.security

import com.android.sample.data.repository.parseEmulatorHostAndPort
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.robolectric.RuntimeEnvironment

/** Runs [test] with one Firebase client per configuration, signed in anonymously when `true`. */
internal suspend fun withClients(
    vararg configurations: Pair<String, Boolean>,
    test: suspend (List<EmulatorClient>) -> Unit,
) {
  val (firestoreEndpoint, authEndpoint) = requireEmulatorEndpoints()
  val clients = mutableListOf<EmulatorClient>()
  try {
    configurations.mapTo(clients) { (name, authenticate) ->
      createClient(name, firestoreEndpoint, authEndpoint, authenticate)
    }
    test(clients)
  } finally {
    clients.asReversed().forEach { it.close() }
  }
}

internal suspend fun assertPermissionDenied(
    description: String = "operation",
    operation: suspend () -> Unit,
) {
  try {
    operation()
    fail("Expected Firestore security rules to deny $description")
  } catch (exception: FirebaseFirestoreException) {
    assertEquals(
        "Unexpected result for $description",
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        exception.code,
    )
  }
}

/**
 * Creates `[collectionPath]/[documentId]` as the emulator owner, bypassing the security rules, for
 * documents that clients may not write.
 */
internal fun createDocumentAsOwner(
    collectionPath: String,
    documentId: String,
    fields: Map<String, Any?>,
) =
    firestoreOwnerRequest(
        "POST",
        "$collectionPath?documentId=$documentId",
        JSONObject().put("fields", fields.toFirestoreFields()),
    )

/** Deletes the document at [documentPath] as the emulator owner, bypassing the security rules. */
internal fun deleteDocumentAsOwner(documentPath: String) =
    firestoreOwnerRequest("DELETE", documentPath, body = null)

internal data class EmulatorClient(
    val app: FirebaseApp,
    val auth: FirebaseAuth,
    val firestore: FirebaseFirestore,
) {
  suspend fun close() {
    auth.signOut()
    firestore.terminate().await()
    app.delete()
  }
}

private fun requireEmulatorEndpoints(): Pair<Pair<String, Int>, Pair<String, Int>> {
  val firestoreAddress = System.getenv(FIRESTORE_EMULATOR_HOST_ENVIRONMENT_VARIABLE)
  val authAddress = System.getenv(FIREBASE_AUTH_EMULATOR_HOST_ENVIRONMENT_VARIABLE)
  assumeTrue(
      "Start both the Firestore and Auth emulators to run production rules tests",
      !firestoreAddress.isNullOrBlank() && !authAddress.isNullOrBlank(),
  )
  return checkNotNull(firestoreAddress).parseEmulatorHostAndPort() to
      checkNotNull(authAddress).parseEmulatorHostAndPort()
}

private suspend fun createClient(
    name: String,
    firestoreEndpoint: Pair<String, Int>,
    authEndpoint: Pair<String, Int>,
    authenticate: Boolean = false,
): EmulatorClient {
  val app = createFirebaseApp(name)
  val auth = FirebaseAuth.getInstance(app)
  auth.useEmulator(authEndpoint.first, authEndpoint.second)
  if (authenticate) {
    auth.signInAnonymously().await()
  }
  val firestore = FirebaseFirestore.getInstance(app)
  firestore.firestoreSettings =
      FirebaseFirestoreSettings.Builder()
          .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
          .build()
  firestore.useEmulator(firestoreEndpoint.first, firestoreEndpoint.second)
  return EmulatorClient(app, auth, firestore)
}

private fun createFirebaseApp(name: String): FirebaseApp {
  val options =
      FirebaseOptions.Builder()
          .setApplicationId("1:123456789:android:firestore-rules-test")
          .setApiKey("fake-api-key")
          .setProjectId(PROJECT_ID)
          .build()
  return FirebaseApp.initializeApp(
      RuntimeEnvironment.getApplication(),
      options,
      "firestore-rules-$name-${UUID.randomUUID()}",
  )
}

private fun firestoreOwnerRequest(method: String, path: String, body: JSONObject?) {
  val (host, port) =
      checkNotNull(System.getenv(FIRESTORE_EMULATOR_HOST_ENVIRONMENT_VARIABLE))
          .parseEmulatorHostAndPort()
  val url = URL("http://$host:$port/v1/projects/$PROJECT_ID/databases/(default)/documents/$path")
  val connection = url.openConnection() as HttpURLConnection
  try {
    connection.requestMethod = method
    connection.connectTimeout = OWNER_REQUEST_TIMEOUT_MS
    connection.readTimeout = OWNER_REQUEST_TIMEOUT_MS
    // The emulator accepts this fixed admin token; it is not a secret.
    connection.setRequestProperty("Authorization", "Bearer owner")
    if (body != null) {
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json")
      connection.outputStream.use { it.write(body.toString().toByteArray()) }
    }
    val code = connection.responseCode
    check(code in 200..299) { "Firestore emulator $method $path failed (HTTP $code)." }
  } finally {
    connection.disconnect()
  }
}

/** Encodes [this] in the Firestore REST format for the values that the tests store. */
private fun Map<String, Any?>.toFirestoreFields(): JSONObject =
    JSONObject().also { fields ->
      forEach { (name, value) -> fields.put(name, value.toFirestoreValue()) }
    }

private fun Any?.toFirestoreValue(): JSONObject =
    when (this) {
      null -> JSONObject().put("nullValue", JSONObject.NULL)
      is Boolean -> JSONObject().put("booleanValue", this)
      is String -> JSONObject().put("stringValue", this)
      is Timestamp ->
          JSONObject()
              .put(
                  "timestampValue",
                  java.time.Instant.ofEpochSecond(seconds, nanoseconds.toLong()).toString(),
              )
      is Int,
      is Long -> JSONObject().put("integerValue", toString())
      is Number -> JSONObject().put("doubleValue", toDouble())
      is List<*> ->
          JSONObject()
              .put(
                  "arrayValue",
                  JSONObject().put("values", JSONArray(map { it.toFirestoreValue() })),
              )
      is Map<*, *> ->
          JSONObject()
              .put(
                  "mapValue",
                  JSONObject()
                      .put("fields", entries.associate { (k, v) -> "$k" to v }.toFirestoreFields()),
              )
      else -> error("Unsupported Firestore test value: $this")
    }

private const val PROJECT_ID = "demo-commando"
private const val OWNER_REQUEST_TIMEOUT_MS = 10_000
private const val FIRESTORE_EMULATOR_HOST_ENVIRONMENT_VARIABLE = "FIRESTORE_EMULATOR_HOST"
private const val FIREBASE_AUTH_EMULATOR_HOST_ENVIRONMENT_VARIABLE = "FIREBASE_AUTH_EMULATOR_HOST"
