package com.android.sample.emulator.firestore.adapter

// AI assistance: Claude Code for idempotent and offline publication tests (#394).
import com.commando.app.data.repository.FirebaseTripFirestoreDataSource
import com.commando.app.data.repository.TripError
import com.commando.app.data.repository.TripRepositoryFirestore
import com.commando.app.data.repository.TripResult
import com.commando.app.data.repository.parseEmulatorHostAndPort
import com.commando.app.data.repository.successData
import com.commando.app.model.Location
import com.commando.app.model.NewTrip
import com.commando.app.model.Trip
import com.commando.app.model.TripStatus
import com.commando.app.model.authentication.AuthUser
import com.commando.app.model.authentication.FakeAuthRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import java.time.Instant
import java.util.UUID
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FirebaseTripFirestoreDataSourceEmulatorTest {

  private val currentTime = Instant.parse("2026-10-04T12:00:00.123456Z")
  private val store = Location(name = "Migros", latitude = 46.5191, longitude = 6.6332)
  private val handoff = Location(name = "EPFL", latitude = 46.5188, longitude = 6.5665)

  @Test
  fun concreteAdapter_persistsAndRoundTripsSchema() = runTest {
    withFirestoreEmulator { firestore ->
      val dataSource = FirebaseTripFirestoreDataSource(firestore)
      val repository = createRepository(dataSource)
      val newTrip =
          NewTrip(
              store = store,
              scheduledAt = currentTime.plusSeconds(3_600),
              handoffLocation = handoff,
          )

      val publishedTrip = repository.publishTrip(newTrip).successData()

      assertTrue(publishedTrip.id.isNotBlank())
      assertEquals(AUTHENTICATED_USER_ID, publishedTrip.ownerId)
      assertEquals(currentTime, publishedTrip.createdAt)
      assertEquals(publishedTrip.createdAt, publishedTrip.updatedAt)
      val storedSnapshot =
          firestore.collection(TRIPS_COLLECTION).document(publishedTrip.id).get().await()
      assertTrue(storedSnapshot.exists())
      assertEquals(AUTHENTICATED_USER_ID, storedSnapshot.getString("ownerId"))
      assertEquals(
          Timestamp(newTrip.scheduledAt.epochSecond, newTrip.scheduledAt.nano),
          storedSnapshot.getTimestamp("scheduledAt"),
      )
      assertEquals(store.toStoredLocation(), storedSnapshot.get("store"))
      assertEquals(handoff.toStoredLocation(), storedSnapshot.get("handoffLocation"))
      assertEquals("PUBLISHED", storedSnapshot.getString("status"))

      val reloadedRepository =
          TripRepositoryFirestore(
              authRepository = FakeAuthRepository(AuthUser(uid = AUTHENTICATED_USER_ID)),
              firestore = firestore,
              now = { currentTime },
          )
      assertEquals(
          TripResult.Success(publishedTrip),
          reloadedRepository.getTripById(publishedTrip.id),
      )
      assertNull(dataSource.getTrip("missing-document"))
    }
  }

  @Test
  fun concreteAdapter_queriesAllUpcomingTripsInOrder() = runTest {
    withFirestoreEmulator { firestore ->
      val dataSource = FirebaseTripFirestoreDataSource(firestore)
      val repository = createRepository(dataSource)
      val expectedUpcomingTimes = (1L..55L).map(currentTime::plusSeconds)
      repeat(55) { index ->
        dataSource.createStoredTrip(
            ownerId = OTHER_USER_ID,
            scheduledAt = currentTime.plusSeconds((index + 1).toLong()),
        )
      }
      val excludedIds =
          listOf(
              dataSource.createStoredTrip(
                  scheduledAt = currentTime.minusSeconds(1),
              ),
              dataSource.createStoredTrip(
                  scheduledAt = currentTime,
              ),
              dataSource.createStoredTrip(
                  ownerId = OTHER_USER_ID,
                  scheduledAt = currentTime.plusSeconds(7_200),
                  status = TripStatus.COMPLETED,
              ),
          )

      val upcomingTrips = repository.getUpcomingTrips().successData()

      assertEquals(55, upcomingTrips.size)
      assertEquals(expectedUpcomingTimes, upcomingTrips.map(Trip::scheduledAt))
      assertTrue(upcomingTrips.all { it.status == TripStatus.PUBLISHED })
      assertTrue(upcomingTrips.none { it.id in excludedIds })
    }
  }

  @Test
  fun concreteAdapter_queriesOwnedTripsAcrossDatesAndStatuses() = runTest {
    withFirestoreEmulator { firestore ->
      val dataSource = FirebaseTripFirestoreDataSource(firestore)
      val repository = createRepository(dataSource)
      val publishedId = dataSource.createStoredTrip(scheduledAt = currentTime.plusSeconds(3_600))
      val pastOwnedId =
          dataSource.createStoredTrip(
              scheduledAt = currentTime.minusSeconds(1),
              status = TripStatus.CANCELLED,
          )
      val otherUserId =
          dataSource.createStoredTrip(
              ownerId = OTHER_USER_ID,
              scheduledAt = currentTime.plusSeconds(10_800),
              status = TripStatus.CANCELLED,
          )

      val myTrips = repository.getMyTrips().successData()

      assertEquals(
          setOf(publishedId, pastOwnedId),
          myTrips.map(Trip::id).toSet(),
      )
      assertEquals(
          setOf(TripStatus.PUBLISHED, TripStatus.CANCELLED),
          myTrips.map(Trip::status).toSet(),
      )
      assertFalse(myTrips.map(Trip::id).contains(otherUserId))
    }
  }

  @Test
  fun concreteAdapter_republishingWithSameId_returnsStoredTripUnchanged() = runTest {
    withFirestoreEmulator { firestore ->
      val dataSource = FirebaseTripFirestoreDataSource(firestore)
      // Nanosecond input: Firestore stores microseconds, and the retry must still match.
      val newTrip = NewTrip(store, currentTime.plusSeconds(3_600).plusNanos(789), handoff)
      val tripId = "intent-${UUID.randomUUID()}"
      val reference = firestore.collection(TRIPS_COLLECTION).document(tripId)

      val first = createRepository(dataSource).publishTrip(tripId, newTrip).successData()
      val stored = reference.get(Source.SERVER).await().data
      val later =
          TripRepositoryFirestore(
              dataSource = dataSource,
              authRepository = FakeAuthRepository(AuthUser(uid = AUTHENTICATED_USER_ID)),
              now = { currentTime.plusSeconds(60) },
          )
      val retry = later.publishTrip(tripId, newTrip).successData()

      assertEquals(first.id, retry.id)
      assertEquals(currentTime, retry.createdAt)
      assertEquals(stored, reference.get(Source.SERVER).await().data)
    }
  }

  @Test
  fun concreteAdapter_offlinePublishFailsAndLeavesNoTripAfterReconnection() = runTest {
    // disableNetwork() does not stop transaction RPCs (they commit), so offline is an unreachable
    // endpoint for the same project.
    val projectId = "trip-${UUID.randomUUID().toString().take(20)}"
    val newTrip = NewTrip(store, currentTime.plusSeconds(3_600), handoff)
    val tripId = "offline-${UUID.randomUUID()}"
    withFirestoreEmulator(projectId, port = UNREACHABLE_PORT) { offline ->
      val dataSource = FirebaseTripFirestoreDataSource(offline)
      // Real time, not virtual: the SDK fails on its own clock.
      val started = TimeSource.Monotonic.markNow()
      val failure =
          withContext(Dispatchers.Default) {
            withTimeout(OFFLINE_TIMEOUT_MS) {
              runCatching {
                dataSource.createTripIfAbsent(
                    tripId,
                    storedTrip(AUTHENTICATED_USER_ID, newTrip.scheduledAt),
                )
              }
            }
          }
      val elapsed = started.elapsedNow()
      val result =
          withContext(Dispatchers.Default) {
            withTimeout(OFFLINE_TIMEOUT_MS) {
              createRepository(dataSource).publishTrip(tripId, newTrip)
            }
          }
      val code = (failure.exceptionOrNull() as? FirebaseFirestoreException)?.code
      println("Offline publish: $code after $elapsed (${failure.exceptionOrNull()}); $result")

      assertEquals(FirebaseFirestoreException.Code.UNAVAILABLE, code)
      assertEquals(TripResult.Error(TripError.NetworkError), result)
      // Completes at once only if nothing was queued for replay on reconnection.
      withContext(Dispatchers.Default) {
        withTimeout(OFFLINE_TIMEOUT_MS) { offline.waitForPendingWrites().await() }
      }
    }
    withFirestoreEmulator(projectId) { online ->
      val reference = online.collection(TRIPS_COLLECTION).document(tripId)
      assertFalse(reference.get(Source.SERVER).await().exists())
    }
  }

  private fun createRepository(dataSource: FirebaseTripFirestoreDataSource) =
      TripRepositoryFirestore(
          dataSource = dataSource,
          authRepository = FakeAuthRepository(AuthUser(uid = AUTHENTICATED_USER_ID)),
          now = { currentTime },
      )

  private suspend fun withFirestoreEmulator(
      projectId: String = "trip-${UUID.randomUUID().toString().take(20)}",
      port: Int? = null,
      test: suspend (FirebaseFirestore) -> Unit,
  ) {
    val emulatorAddress = System.getenv(FIRESTORE_EMULATOR_HOST_ENVIRONMENT_VARIABLE)
    assumeTrue(
        "Set FIRESTORE_EMULATOR_HOST to run the concrete Firebase adapter integration tests",
        !emulatorAddress.isNullOrBlank(),
    )
    val (host, emulatorPort) = checkNotNull(emulatorAddress).parseEmulatorHostAndPort()
    val app = createFirebaseApp(projectId)
    val firestore = FirebaseFirestore.getInstance(app)
    firestore.firestoreSettings =
        FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
    firestore.useEmulator(host, port ?: emulatorPort)

    try {
      test(firestore)
    } finally {
      firestore.terminate().await()
      app.delete()
    }
  }

  private fun createFirebaseApp(projectId: String): FirebaseApp {
    val options =
        FirebaseOptions.Builder()
            .setApplicationId("1:123456789:android:trip-repository-test")
            .setApiKey("fake-api-key")
            .setProjectId(projectId)
            .build()
    return FirebaseApp.initializeApp(
        RuntimeEnvironment.getApplication(),
        options,
        "trip-repository-test-${UUID.randomUUID()}",
    )
  }

  private fun storedTrip(
      ownerId: String,
      scheduledAt: Instant,
      status: TripStatus = TripStatus.PUBLISHED,
  ): Map<String, Any?> =
      mapOf(
          "ownerId" to ownerId,
          "store" to store.toStoredLocation(),
          "scheduledAt" to Timestamp(scheduledAt.epochSecond, scheduledAt.nano),
          "handoffLocation" to handoff.toStoredLocation(),
          "status" to status.name,
          "createdAt" to Timestamp(currentTime.minusSeconds(3_600).epochSecond, currentTime.nano),
          "updatedAt" to Timestamp(currentTime.minusSeconds(1_800).epochSecond, currentTime.nano),
      )

  private suspend fun FirebaseTripFirestoreDataSource.createStoredTrip(
      ownerId: String = AUTHENTICATED_USER_ID,
      scheduledAt: Instant,
      status: TripStatus = TripStatus.PUBLISHED,
  ): String =
      createTripIfAbsent(UUID.randomUUID().toString(), storedTrip(ownerId, scheduledAt, status)).id

  private fun Location.toStoredLocation(): Map<String, Any> =
      mapOf("name" to name, "latitude" to latitude, "longitude" to longitude)

  private companion object {
    const val FIRESTORE_EMULATOR_HOST_ENVIRONMENT_VARIABLE = "FIRESTORE_EMULATOR_HOST"
    const val TRIPS_COLLECTION = "trips"
    const val AUTHENTICATED_USER_ID = "authenticated-user"
    const val OTHER_USER_ID = "other-user"
    const val OFFLINE_TIMEOUT_MS = 30_000L
    /** Nothing listens on the TCP port 1 of the emulator host, so every RPC fails. */
    const val UNREACHABLE_PORT = 1
  }
}
