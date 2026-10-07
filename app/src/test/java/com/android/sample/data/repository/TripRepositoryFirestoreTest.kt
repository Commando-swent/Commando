package com.android.sample.data.repository

import com.android.sample.model.Location
import com.android.sample.model.NewTrip
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TripRepositoryFirestoreTest {

  private val currentTime = Instant.parse("2026-10-04T12:00:00Z")
  private val store = Location(name = "Migros", latitude = 46.5191, longitude = 6.6332)
  private val handoff = Location(name = "EPFL", latitude = 46.5188, longitude = 6.5665)

  @Test
  fun publishTrip_withValidData_persistsFirestoreSchemaAndReturnsRoundTrip() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource(nextDocumentId = "firestore-document-id")
    val repository = createRepository(dataSource)
    val newTrip = newTripAt(currentTime.plusSeconds(3_600))

    val trip = repository.publishTrip(newTrip).successData()

    assertEquals("firestore-document-id", trip.id)
    assertEquals("authenticated-user", trip.ownerId)
    assertEquals(store, trip.store)
    assertEquals(newTrip.scheduledAt, trip.scheduledAt)
    assertEquals(handoff, trip.handoffLocation)
    assertEquals(TripStatus.PUBLISHED, trip.status)
    assertEquals(currentTime, trip.createdAt)
    assertEquals(trip.createdAt, trip.updatedAt)

    val storedData = checkNotNull(dataSource.documents[trip.id])
    assertEquals("authenticated-user", storedData["ownerId"])
    assertEquals(store.toStoredLocation(), storedData["store"])
    assertEquals(
        Timestamp(newTrip.scheduledAt.epochSecond, newTrip.scheduledAt.nano),
        storedData["scheduledAt"],
    )
    assertEquals(handoff.toStoredLocation(), storedData["handoffLocation"])
    assertEquals("PUBLISHED", storedData["status"])
    assertEquals(Timestamp(currentTime.epochSecond, currentTime.nano), storedData["createdAt"])
    assertEquals(storedData["createdAt"], storedData["updatedAt"])
  }

  @Test
  fun publishTrip_rejectsInvalidLocationsAndNonFutureSchedulesWithoutWriting() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    val repository = createRepository(dataSource)
    val validTrip = newTripAt(currentTime.plusSeconds(3_600))
    val invalidTrips =
        listOf(
            "blank store name" to validTrip.copy(store = store.copy(name = "   ")),
            "blank handoff name" to validTrip.copy(handoffLocation = handoff.copy(name = "")),
            "store latitude below minimum" to validTrip.copy(store = store.copy(latitude = -90.1)),
            "store longitude below minimum" to
                validTrip.copy(store = store.copy(longitude = -180.1)),
            "NaN handoff latitude" to
                validTrip.copy(handoffLocation = handoff.copy(latitude = Double.NaN)),
            "schedule equal to now" to validTrip.copy(scheduledAt = currentTime),
        )

    invalidTrips.forEach { (description, invalidTrip) ->
      assertTripError(TripError.InvalidData, repository.publishTrip(invalidTrip), description)
    }

    assertEquals(0, dataSource.createCalls)
    assertTrue(dataSource.documents.isEmpty())
  }

  @Test
  fun publishTrip_acceptsCoordinateBoundaries() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    val repository = createRepository(dataSource)
    val minimum = Location("Southwest", latitude = -90.0, longitude = -180.0)
    val maximum = Location("Northeast", latitude = 90.0, longitude = 180.0)

    assertTrue(
        repository.publishTrip(newTripAt(currentTime.plusNanos(1)).copy(store = minimum))
            is TripResult.Success
    )
    assertTrue(
        repository.publishTrip(newTripAt(currentTime.plusNanos(1)).copy(handoffLocation = maximum))
            is TripResult.Success
    )
  }

  @Test
  fun publishTrip_withoutAuthenticatedUser_returnsPermissionDeniedWithoutWriting() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    val repository = createRepository(dataSource, userId = null)

    assertTripError(
        TripError.PermissionDenied,
        repository.publishTrip(newTripAt(currentTime.plusSeconds(1))),
    )
    assertEquals(0, dataSource.createCalls)
    assertTrue(dataSource.documents.isEmpty())
  }

  @Test
  fun publishTrip_readsOwnerFromCurrentAuthRepositorySession() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    val authRepository = FakeAuthRepository(AuthUser(uid = "first-owner"))
    val repository =
        TripRepositoryFirestore(
            dataSource = dataSource,
            authRepository = authRepository,
            now = { currentTime },
        )

    val first = repository.publishTrip(newTripAt(currentTime.plusSeconds(1))).successData()
    authRepository.signInWithEmailResult = Result.success(AuthUser(uid = "second-owner"))
    authRepository.signInWithEmail("ignored@example.com", "ignored")
    val second = repository.publishTrip(newTripAt(currentTime.plusSeconds(2))).successData()

    assertEquals("first-owner", first.ownerId)
    assertEquals("first-owner", dataSource.documents[first.id]?.get("ownerId"))
    assertEquals("second-owner", second.ownerId)
    assertEquals("second-owner", dataSource.documents[second.id]?.get("ownerId"))
  }

  @Test
  fun publishTrip_whenWriteFails_returnsMappedErrorWithoutPersisting() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    dataSource.failure = firebaseException(Code.PERMISSION_DENIED)
    val repository = createRepository(dataSource)

    assertTripError(
        TripError.PermissionDenied,
        repository.publishTrip(newTripAt(currentTime.plusSeconds(1))),
    )
    assertTrue(dataSource.documents.isEmpty())
  }

  @Test
  fun publishTrip_returnsSuccessOnlyAfterFirestoreWriteCompletes() = runTest {
    val writeGate = CompletableDeferred<Unit>()
    val dataSource = InMemoryTripFirestoreDataSource().apply { createGate = writeGate }

    val publication =
        async(start = CoroutineStart.UNDISPATCHED) {
          createRepository(dataSource).publishTrip(newTripAt(currentTime.plusSeconds(1)))
        }

    assertEquals(1, dataSource.createCalls)
    assertFalse(publication.isCompleted)
    assertTrue(dataSource.documents.isEmpty())

    writeGate.complete(Unit)

    assertTrue(publication.await() is TripResult.Success)
    assertEquals(1, dataSource.documents.size)
  }

  @Test
  fun publishTrip_whenFirestoreReportsNotFound_returnsUnknownInsteadOfMissingTrip() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    dataSource.failure = firebaseException(Code.NOT_FOUND)

    assertTripError(
        TripError.Unknown,
        createRepository(dataSource).publishTrip(newTripAt(currentTime.plusSeconds(1))),
    )
    assertTrue(dataSource.documents.isEmpty())
  }

  @Test
  fun getTripById_mapsDocumentIdAndAllRequiredFields() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    dataSource.documents["document-id"] =
        validStoredTrip(ownerId = "someone-else") + ("id" to "ignored")
    val repository = createRepository(dataSource)

    val trip = repository.getTripById("document-id").successData()

    assertEquals("document-id", trip.id)
    assertEquals("someone-else", trip.ownerId)
    assertEquals(store, trip.store)
    assertEquals(currentTime.plusSeconds(3_600), trip.scheduledAt)
    assertEquals(handoff, trip.handoffLocation)
    assertEquals(TripStatus.PUBLISHED, trip.status)
    assertEquals(currentTime.minusSeconds(3_600), trip.createdAt)
    assertEquals(currentTime.minusSeconds(1_800), trip.updatedAt)
  }

  @Test
  fun getTripById_withBlankId_returnsNotFoundWithoutQuerying() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    val repository = createRepository(dataSource)

    assertTripError(TripError.NotFound, repository.getTripById(""))

    assertEquals(0, dataSource.getTripCalls)
  }

  @Test
  fun getTripById_withMissingDocument_returnsNotFoundAfterQuerying() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()

    assertTripError(TripError.NotFound, createRepository(dataSource).getTripById("missing"))
    assertEquals(1, dataSource.getTripCalls)
  }

  @Test
  fun getTripById_withMalformedRequiredData_returnsInvalidData() = runTest {
    val validData = validStoredTrip()
    val malformedDocuments =
        listOf(
            "missing ownerId" to (validData - "ownerId"),
            "missing store" to (validData - "store"),
            "non-map store" to (validData + ("store" to "not-a-map")),
            "missing store name" to
                (validData + ("store" to store.toStoredLocation().minus("name"))),
            "blank store name" to
                (validData + ("store" to store.copy(name = " ").toStoredLocation())),
            "missing store latitude" to
                (validData + ("store" to store.toStoredLocation().minus("latitude"))),
            "non-number store latitude" to
                (validData + ("store" to (store.toStoredLocation() + ("latitude" to "north")))),
            "out-of-range store latitude" to
                (validData + ("store" to store.copy(latitude = 90.1).toStoredLocation())),
            "missing scheduledAt" to (validData - "scheduledAt"),
            "non-timestamp scheduledAt" to (validData + ("scheduledAt" to "not-a-timestamp")),
            "missing handoffLocation" to (validData - "handoffLocation"),
            "missing status" to (validData - "status"),
            "unknown status" to (validData + ("status" to "NOT_A_STATUS")),
            "missing createdAt" to (validData - "createdAt"),
            "missing updatedAt" to (validData - "updatedAt"),
        )

    malformedDocuments.forEachIndexed { index, (description, malformedData) ->
      val dataSource = InMemoryTripFirestoreDataSource()
      val documentId = "malformed-$index"
      dataSource.documents[documentId] = malformedData

      assertTripError(
          TripError.InvalidData,
          createRepository(dataSource).getTripById(documentId),
          description,
      )
    }
  }

  @Test
  fun getTripById_acceptsFirestoreIntegerCoordinates() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    val integerLocation =
        mapOf("name" to "Integer coordinates", "latitude" to 46L, "longitude" to 6L)
    dataSource.documents["integer-coordinates"] =
        validStoredTrip() +
            mapOf(
                "store" to integerLocation,
                "handoffLocation" to integerLocation,
            )

    val trip = createRepository(dataSource).getTripById("integer-coordinates").successData()

    assertEquals(Location("Integer coordinates", 46.0, 6.0), trip.store)
    assertEquals(Location("Integer coordinates", 46.0, 6.0), trip.handoffLocation)
  }

  @Test
  fun getUpcomingTrips_queriesPublishedFutureTripsAndMapsReturnedOrder() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    dataSource.upcomingDocuments =
        listOf(
            storedDocument(
                "earlier",
                validStoredTrip(scheduledAt = currentTime.plusSeconds(3_600)),
            ),
            storedDocument(
                "later",
                validStoredTrip(scheduledAt = currentTime.plusSeconds(7_200)),
            ),
        )

    val trips = createRepository(dataSource).getUpcomingTrips().successData()

    assertEquals(listOf("earlier", "later"), trips.map(Trip::id))
    assertEquals("PUBLISHED", dataSource.lastUpcomingStatus)
    assertEquals(currentTime, dataSource.lastScheduledAfter)
  }

  @Test
  fun getUpcomingTrips_withNoMatches_returnsEmptySuccess() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()

    assertEquals(
        TripResult.Success(emptyList<Trip>()),
        createRepository(dataSource).getUpcomingTrips(),
    )
  }

  @Test
  fun getUpcomingTrips_withMalformedMatchingDocument_returnsInvalidData() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    dataSource.upcomingDocuments =
        listOf(storedDocument("malformed", validStoredTrip() - "ownerId"))

    assertTripError(TripError.InvalidData, createRepository(dataSource).getUpcomingTrips())
  }

  @Test
  fun getUpcomingTrips_whenFirestoreIsUnavailable_returnsNetworkError() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    dataSource.failure = firebaseException(Code.UNAVAILABLE)

    assertTripError(TripError.NetworkError, createRepository(dataSource).getUpcomingTrips())
  }

  @Test
  fun getMyTrips_queriesCurrentOwnerAndMapsAllDatesAndStatuses() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    val mine =
        listOf(
            "mine-published" to
                validStoredTrip(
                    ownerId = "authenticated-user",
                    scheduledAt = currentTime.minusSeconds(1),
                ),
            "mine-cancelled" to
                validStoredTrip(
                    ownerId = "authenticated-user",
                    scheduledAt = currentTime.plusSeconds(1),
                    status = TripStatus.CANCELLED,
                ),
        )
    dataSource.ownerDocuments = mine.map { (id, data) -> storedDocument(id, data) }

    val trips = createRepository(dataSource).getMyTrips().successData()

    assertEquals(mine.map { it.first }, trips.map(Trip::id))
    assertEquals(listOf(TripStatus.PUBLISHED, TripStatus.CANCELLED), trips.map(Trip::status))
    assertEquals("authenticated-user", dataSource.lastOwnerId)
  }

  @Test
  fun getMyTrips_withoutAuthenticatedUser_returnsPermissionDeniedWithoutQuerying() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    val repository = createRepository(dataSource, userId = null)

    assertTripError(TripError.PermissionDenied, repository.getMyTrips())
    assertEquals(0, dataSource.ownerQueryCalls)
  }

  @Test
  fun getMyTrips_whenPermissionIsDenied_returnsPermissionDenied() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    dataSource.failure = firebaseException(Code.PERMISSION_DENIED)

    assertTripError(TripError.PermissionDenied, createRepository(dataSource).getMyTrips())
  }

  @Test
  fun firestoreAndNetworkFailures_mapToExistingTripErrors() = runTest {
    val cases =
        listOf(
            firebaseException(Code.PERMISSION_DENIED) to TripError.PermissionDenied,
            firebaseException(Code.UNAUTHENTICATED) to TripError.PermissionDenied,
            firebaseException(Code.UNAVAILABLE) to TripError.NetworkError,
            firebaseException(Code.DEADLINE_EXCEEDED) to TripError.NetworkError,
            firebaseException(Code.NOT_FOUND) to TripError.NotFound,
            firebaseException(Code.INVALID_ARGUMENT) to TripError.InvalidData,
            IOException("offline") to TripError.NetworkError,
            firebaseException(Code.FAILED_PRECONDITION) to TripError.Unknown,
            IllegalStateException("unexpected") to TripError.Unknown,
        )

    cases.forEach { (exception, expectedError) ->
      val dataSource = InMemoryTripFirestoreDataSource()
      dataSource.failure = exception
      assertTripError(
          expectedError,
          createRepository(dataSource).getTripById("trip"),
          "Unexpected mapping for ${exception.message}",
      )
    }
  }

  @Test
  fun getUpcomingTrips_whenFirestoreReportsNotFound_returnsUnknown() = runTest {
    val repository =
        createRepository(
            InMemoryTripFirestoreDataSource().apply { failure = firebaseException(Code.NOT_FOUND) }
        )

    assertTripError(
        TripError.Unknown,
        repository.getUpcomingTrips(),
        "getUpcomingTrips must not report a missing trip",
    )
  }

  @Test
  fun cancellationException_isRethrownInsteadOfMappedToUnknown() = runTest {
    val dataSource = InMemoryTripFirestoreDataSource()
    dataSource.failure = CancellationException("cancelled")

    try {
      createRepository(dataSource).getTripById("trip")
      fail("Expected CancellationException to be rethrown")
    } catch (_: CancellationException) {
      // Expected: cancellation must remain visible to the calling coroutine.
    }
  }

  private fun createRepository(
      dataSource: InMemoryTripFirestoreDataSource,
      userId: String? = "authenticated-user",
      clock: () -> Instant = { currentTime },
  ) =
      TripRepositoryFirestore(
          dataSource = dataSource,
          authRepository = fakeAuthRepository(userId),
          now = clock,
      )

  private fun fakeAuthRepository(userId: String?): FakeAuthRepository =
      FakeAuthRepository(userId?.let { AuthUser(uid = it) })

  private fun firebaseException(code: Code) = FirebaseFirestoreException(code.name, code)

  private fun assertTripError(
      expected: TripError,
      actual: TripResult<*>,
      message: String = "Unexpected TripResult",
  ) = assertEquals(message, TripResult.Error(expected), actual)

  private fun newTripAt(scheduledAt: Instant) =
      NewTrip(store = store, scheduledAt = scheduledAt, handoffLocation = handoff)

  private fun validStoredTrip(
      ownerId: String = "authenticated-user",
      scheduledAt: Instant = currentTime.plusSeconds(3_600),
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

  private fun storedDocument(id: String, data: Map<String, Any?>): TripFirestoreDocument =
      TripFirestoreDocument(id = id, data = data)

  private fun Location.toStoredLocation(): Map<String, Any> =
      mapOf(
          "name" to name,
          "latitude" to latitude,
          "longitude" to longitude,
      )

  private class InMemoryTripFirestoreDataSource(
      private var nextDocumentId: String = "generated-1"
  ) : TripFirestoreDataSource {
    val documents = linkedMapOf<String, Map<String, Any?>>()
    var upcomingDocuments = emptyList<TripFirestoreDocument>()
    var ownerDocuments = emptyList<TripFirestoreDocument>()
    var failure: Exception? = null
    var createGate: CompletableDeferred<Unit>? = null
    var createCalls = 0
    var getTripCalls = 0
    var ownerQueryCalls = 0
    var lastUpcomingStatus: String? = null
    var lastScheduledAfter: Instant? = null
    var lastOwnerId: String? = null

    override suspend fun createTrip(data: Map<String, Any?>): String {
      createCalls += 1
      throwFailureIfPresent()
      createGate?.await()
      val documentId = nextDocumentId
      documents[documentId] = data
      nextDocumentId = "generated-${createCalls + 1}"
      return documentId
    }

    override suspend fun getTrip(tripId: String): TripFirestoreDocument? {
      getTripCalls += 1
      throwFailureIfPresent()
      return documents[tripId]?.let { TripFirestoreDocument(id = tripId, data = it) }
    }

    override suspend fun getUpcomingTrips(
        status: String,
        scheduledAfter: Instant,
    ): List<TripFirestoreDocument> {
      throwFailureIfPresent()
      lastUpcomingStatus = status
      lastScheduledAfter = scheduledAfter
      return upcomingDocuments
    }

    override suspend fun getTripsByOwner(ownerId: String): List<TripFirestoreDocument> {
      ownerQueryCalls += 1
      throwFailureIfPresent()
      lastOwnerId = ownerId
      return ownerDocuments
    }

    private fun throwFailureIfPresent() {
      failure?.let { throw it }
    }
  }
}
