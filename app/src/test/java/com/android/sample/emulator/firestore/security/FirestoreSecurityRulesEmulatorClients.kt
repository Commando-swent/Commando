package com.android.sample.emulator.firestore.security

import com.android.sample.data.repository.parseEmulatorHostAndPort
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import java.util.UUID
import kotlinx.coroutines.tasks.await
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

private const val PROJECT_ID = "demo-commando"
private const val FIRESTORE_EMULATOR_HOST_ENVIRONMENT_VARIABLE = "FIRESTORE_EMULATOR_HOST"
private const val FIREBASE_AUTH_EMULATOR_HOST_ENVIRONMENT_VARIABLE = "FIREBASE_AUTH_EMULATOR_HOST"
