package com.android.sample.emulator.firestore.security

import com.android.sample.data.repository.TripRepositoryFirestore
import com.android.sample.data.repository.parseEmulatorHostAndPort
import com.android.sample.data.repository.successData
import com.android.sample.model.Location
import com.android.sample.model.NewTrip
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TripFirestoreSecurityRulesEmulatorTest {

  @Test
  fun create_requiresAuthenticationAndMatchingOwner() = runTest {
    withClients(
        "unauthenticated" to false,
        "owner" to true,
        "other" to true,
    ) { (unauthenticated, owner, other) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid
      val otherId = checkNotNull(other.auth.currentUser).uid
      val trips = owner.firestore.collection(TRIPS_COLLECTION)

      assertPermissionDenied {
        unauthenticated.firestore
            .collection(TRIPS_COLLECTION)
            .document("unauthenticated-${UUID.randomUUID()}")
            .set(storedTrip(ownerId))
            .await()
      }
      val publishedTrip =
          TripRepositoryFirestore(
                  authRepository = FakeAuthRepository(AuthUser(uid = ownerId)),
                  firestore = owner.firestore,
                  now = { NOW },
              )
              .publishTrip(
                  NewTrip(
                      store = Location("Migros", latitude = 46.5191, longitude = 6.6332),
                      scheduledAt = NOW.plusSeconds(3_600),
                      handoffLocation = Location("EPFL", latitude = 46.5188, longitude = 6.5665),
                  )
              )
              .successData()
      assertTrue(trips.document(publishedTrip.id).get().await().exists())
      assertPermissionDenied {
        owner.firestore
            .collection(TRIPS_COLLECTION)
            .document("spoofed-${UUID.randomUUID()}")
            .set(storedTrip(otherId))
            .await()
      }
    }
  }

  @Test
  fun read_requiresAuthenticationAndRespectsVisibility() = runTest {
    withClients(
        "unauthenticated" to false,
        "owner" to true,
        "other" to true,
    ) { (unauthenticated, owner, other) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid
      val trips = owner.firestore.collection(TRIPS_COLLECTION)
      val published = trips.document("published-${UUID.randomUUID()}")
      val nonPublished = trips.document("non-published-${UUID.randomUUID()}")

      published.set(storedTrip(ownerId)).await()
      val otherPublished = other.firestore.collection(TRIPS_COLLECTION).document(published.id)
      assertTrue(otherPublished.get().await().exists())
      assertPermissionDenied {
        unauthenticated.firestore.collection(TRIPS_COLLECTION).document(published.id).get().await()
      }

      nonPublished.set(storedTrip(ownerId, status = "IN_PROGRESS")).await()
      assertTrue(nonPublished.get().await().exists())
      assertPermissionDenied {
        other.firestore.collection(TRIPS_COLLECTION).document(nonPublished.id).get().await()
      }
    }
  }

  @Test
  fun getUpcomingTrips_queryIsAuthorizedForAuthenticatedReaders() = runTest {
    withClients("owner" to true, "reader" to true) { (owner, reader) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid
      val readerId = checkNotNull(reader.auth.currentUser).uid
      val tripId = "upcoming-${UUID.randomUUID()}"
      owner.firestore
          .collection(TRIPS_COLLECTION)
          .document(tripId)
          .set(storedTrip(ownerId, scheduledAt = NOW.plusSeconds(3_600)))
          .await()
      val repository =
          TripRepositoryFirestore(
              authRepository = FakeAuthRepository(AuthUser(uid = readerId)),
              firestore = reader.firestore,
              now = { NOW },
          )

      val trips = repository.getUpcomingTrips().successData()

      assertTrue(trips.any { it.id == tripId })
    }
  }

  @Test
  fun getMyTrips_queryIsAuthorizedAndScopedToAuthenticatedOwner() = runTest {
    withClients("owner" to true, "other" to true) { (owner, other) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid
      val otherId = checkNotNull(other.auth.currentUser).uid
      val ownedTripId = "owned-${UUID.randomUUID()}"
      val otherTripId = "other-${UUID.randomUUID()}"
      owner.firestore
          .collection(TRIPS_COLLECTION)
          .document(ownedTripId)
          .set(storedTrip(ownerId, status = "IN_PROGRESS"))
          .await()
      other.firestore
          .collection(TRIPS_COLLECTION)
          .document(otherTripId)
          .set(storedTrip(otherId, status = "CANCELLED"))
          .await()
      val repository =
          TripRepositoryFirestore(
              authRepository = FakeAuthRepository(AuthUser(uid = ownerId)),
              firestore = owner.firestore,
              now = { NOW },
          )

      val trips = repository.getMyTrips().successData()

      assertEquals(listOf(ownedTripId), trips.map { it.id })
    }
  }

  @Test
  fun update_requiresOwnerAndPreservesImmutableFields() = runTest {
    withClients("owner" to true, "other" to true) { (owner, other) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid
      val otherId = checkNotNull(other.auth.currentUser).uid
      val published =
          owner.firestore.collection(TRIPS_COLLECTION).document("update-${UUID.randomUUID()}")
      published.set(storedTrip(ownerId)).await()
      val otherPublished = other.firestore.collection(TRIPS_COLLECTION).document(published.id)

      assertPermissionDenied { otherPublished.update("status", "COMPLETED").await() }
      published.update("status", "COMPLETED").await()
      assertEquals("COMPLETED", published.get().await().getString("status"))
      assertPermissionDenied { published.update("ownerId", otherId).await() }
      assertPermissionDenied {
        published.update("createdAt", Timestamp(NOW.epochSecond + 1, NOW.nano)).await()
      }
    }
  }

  @Test
  fun delete_requiresOwner() = runTest {
    withClients("owner" to true, "other" to true) { (owner, other) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid
      val published =
          owner.firestore.collection(TRIPS_COLLECTION).document("delete-${UUID.randomUUID()}")
      published.set(storedTrip(ownerId, status = "CANCELLED")).await()
      val otherPublished = other.firestore.collection(TRIPS_COLLECTION).document(published.id)

      assertPermissionDenied { otherPublished.delete().await() }
      published.delete().await()
    }
  }

  @Test
  fun productionRules_rejectMalformedTripDocuments() = runTest {
    withClients("malformed-owner" to true) { (owner) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid
      val validTrip = storedTrip(ownerId)
      val malformedTrips =
          listOf(
              "unexpected top-level field" to (validTrip + ("unexpected" to true)),
              "missing required top-level field" to (validTrip - "updatedAt"),
              "unexpected nested location field" to
                  storedTrip(ownerId, store = storeLocation() + ("unexpected" to true)),
              "missing required nested location field" to
                  storedTrip(ownerId, handoffLocation = handoffLocation() - "longitude"),
              "blank location name" to storedTrip(ownerId, store = storeLocation(name = "   ")),
              "latitude outside range" to
                  storedTrip(ownerId, store = storeLocation(latitude = 90.1)),
              "wrong coordinate type" to
                  storedTrip(ownerId, store = storeLocation() + ("latitude" to "north")),
              "longitude outside range" to
                  storedTrip(ownerId, handoffLocation = handoffLocation(longitude = -180.1)),
              "wrong timestamp type" to (validTrip + ("scheduledAt" to "not-a-timestamp")),
              "unknown status" to storedTrip(ownerId, status = "NOT_A_STATUS"),
          )

      malformedTrips.forEachIndexed { index, (description, data) ->
        assertPermissionDenied(description) {
          owner.firestore
              .collection(TRIPS_COLLECTION)
              .document("malformed-$index-${UUID.randomUUID()}")
              .set(data)
              .await()
        }
      }
    }
  }

  private suspend fun withClients(
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
            .setApplicationId("1:123456789:android:trip-rules-test")
            .setApiKey("fake-api-key")
            .setProjectId(PROJECT_ID)
            .build()
    return FirebaseApp.initializeApp(
        RuntimeEnvironment.getApplication(),
        options,
        "trip-rules-$name-${UUID.randomUUID()}",
    )
  }

  private fun storedTrip(
      ownerId: String,
      status: String = "PUBLISHED",
      scheduledAt: Instant = NOW.plusSeconds(3_600),
      store: Map<String, Any> = storeLocation(),
      handoffLocation: Map<String, Any> = handoffLocation(),
  ): Map<String, Any> =
      mapOf(
          "ownerId" to ownerId,
          "store" to store,
          "scheduledAt" to Timestamp(scheduledAt.epochSecond, scheduledAt.nano),
          "handoffLocation" to handoffLocation,
          "status" to status,
          "createdAt" to Timestamp(NOW.epochSecond, NOW.nano),
          "updatedAt" to Timestamp(NOW.epochSecond, NOW.nano),
      )

  private fun storeLocation(
      name: String = "Migros",
      latitude: Double = 46.5191,
      longitude: Double = 6.6332,
  ): Map<String, Any> = mapOf("name" to name, "latitude" to latitude, "longitude" to longitude)

  private fun handoffLocation(
      name: String = "EPFL",
      latitude: Double = 46.5188,
      longitude: Double = 6.5665,
  ): Map<String, Any> = mapOf("name" to name, "latitude" to latitude, "longitude" to longitude)

  private suspend fun assertPermissionDenied(
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

  private data class EmulatorClient(
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

  private companion object {
    val NOW: Instant = Instant.parse("2026-10-04T12:00:00Z")
    const val PROJECT_ID = "demo-commando"
    const val TRIPS_COLLECTION = "trips"
    const val FIRESTORE_EMULATOR_HOST_ENVIRONMENT_VARIABLE = "FIRESTORE_EMULATOR_HOST"
    const val FIREBASE_AUTH_EMULATOR_HOST_ENVIRONMENT_VARIABLE = "FIREBASE_AUTH_EMULATOR_HOST"
  }
}
