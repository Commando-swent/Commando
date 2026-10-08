package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.Location
import com.android.sample.model.NewTrip
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.model.authentication.AuthRepository
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.tasks.await

class TripRepositoryFirestore
internal constructor(
    private val dataSource: TripFirestoreDataSource,
    private val authRepository: AuthRepository,
    private val now: () -> Instant,
) : TripRepository {

  constructor(
      authRepository: AuthRepository,
      firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
      now: () -> Instant = Instant::now,
  ) : this(
      dataSource = FirebaseTripFirestoreDataSource(firestore),
      authRepository = authRepository,
      now = now,
  )

  override suspend fun publishTrip(newTrip: NewTrip): TripResult<Trip> {
    val creationTime = now()
    if (!newTrip.isValid(creationTime)) {
      return TripResult.Error(TripError.InvalidData)
    }

    val ownerId =
        authRepository.currentUser?.uid?.takeIf(String::isNotBlank)
            ?: return TripResult.Error(TripError.PermissionDenied)

    return runRepositoryOperation {
      val data = newTrip.toFirestoreData(ownerId, creationTime)
      val documentId = dataSource.createTrip(data)
      TripResult.Success(
          Trip(
              id = documentId,
              ownerId = ownerId,
              store = newTrip.store,
              scheduledAt = newTrip.scheduledAt,
              handoffLocation = newTrip.handoffLocation,
              status = TripStatus.PUBLISHED,
              createdAt = creationTime,
              updatedAt = creationTime,
          )
      )
    }
  }

  override suspend fun getTripById(tripId: String): TripResult<Trip> {
    if (tripId.isBlank()) {
      return TripResult.Error(TripError.NotFound)
    }

    return runRepositoryOperation(notFoundError = TripError.NotFound) {
      val document = dataSource.getTrip(tripId)
      if (document == null) {
        TripResult.Error(TripError.NotFound)
      } else {
        document.toTrip()?.let { TripResult.Success(it) } ?: TripResult.Error(TripError.InvalidData)
      }
    }
  }

  override suspend fun getUpcomingTrips(): TripResult<List<Trip>> {
    val currentTime = now()
    return runRepositoryOperation {
      dataSource
          .getUpcomingTrips(
              status = TripStatus.PUBLISHED.name,
              scheduledAfter = currentTime,
          )
          .toTripsResult()
    }
  }

  override suspend fun getMyTrips(): TripResult<List<Trip>> {
    val ownerId =
        authRepository.currentUser?.uid?.takeIf(String::isNotBlank)
            ?: return TripResult.Error(TripError.PermissionDenied)

    return runRepositoryOperation { dataSource.getTripsByOwner(ownerId).toTripsResult() }
  }

  private fun NewTrip.isValid(currentTime: Instant): Boolean =
      store.isValid() && handoffLocation.isValid() && scheduledAt > currentTime
}

internal data class TripFirestoreDocument(val id: String, val data: Map<String, Any?>)

internal interface TripFirestoreDataSource {
  suspend fun createTrip(data: Map<String, Any?>): String

  suspend fun getTrip(tripId: String): TripFirestoreDocument?

  suspend fun getUpcomingTrips(
      status: String,
      scheduledAfter: Instant,
  ): List<TripFirestoreDocument>

  suspend fun getTripsByOwner(ownerId: String): List<TripFirestoreDocument>
}

internal class FirebaseTripFirestoreDataSource(firestore: FirebaseFirestore) :
    TripFirestoreDataSource {

  private val trips = firestore.collection(TRIPS_COLLECTION)

  override suspend fun createTrip(data: Map<String, Any?>): String {
    val document = trips.document()
    document.set(data).await()
    return document.id
  }

  override suspend fun getTrip(tripId: String): TripFirestoreDocument? {
    val snapshot = trips.document(tripId).get().await()
    return snapshot
        .takeIf { it.exists() }
        ?.let { TripFirestoreDocument(id = it.id, data = it.data.orEmpty()) }
  }

  override suspend fun getUpcomingTrips(
      status: String,
      scheduledAfter: Instant,
  ): List<TripFirestoreDocument> =
      trips
          .whereEqualTo(FIELD_STATUS, status)
          .whereGreaterThan(FIELD_SCHEDULED_AT, scheduledAfter.toTimestamp())
          .orderBy(FIELD_SCHEDULED_AT, Query.Direction.ASCENDING)
          .get()
          .await()
          .documents
          .map { TripFirestoreDocument(id = it.id, data = it.data.orEmpty()) }

  override suspend fun getTripsByOwner(ownerId: String): List<TripFirestoreDocument> =
      trips.whereEqualTo(FIELD_OWNER_ID, ownerId).get().await().documents.map {
        TripFirestoreDocument(id = it.id, data = it.data.orEmpty())
      }
}

private fun NewTrip.toFirestoreData(ownerId: String, creationTime: Instant): Map<String, Any?> =
    mapOf(
        FIELD_OWNER_ID to ownerId,
        FIELD_STORE to store.toFirestoreData(),
        FIELD_SCHEDULED_AT to scheduledAt.toTimestamp(),
        FIELD_HANDOFF_LOCATION to handoffLocation.toFirestoreData(),
        FIELD_STATUS to TripStatus.PUBLISHED.name,
        FIELD_CREATED_AT to creationTime.toTimestamp(),
        FIELD_UPDATED_AT to creationTime.toTimestamp(),
    )

private fun Location.toFirestoreData(): Map<String, Any> =
    mapOf(
        FIELD_NAME to name,
        FIELD_LATITUDE to latitude,
        FIELD_LONGITUDE to longitude,
    )

private fun TripFirestoreDocument.toTrip(): Trip? {
  val ownerId = data[FIELD_OWNER_ID] as? String ?: return null
  val store = data[FIELD_STORE].toLocation() ?: return null
  val scheduledAt = data[FIELD_SCHEDULED_AT].toInstant() ?: return null
  val handoffLocation = data[FIELD_HANDOFF_LOCATION].toLocation() ?: return null
  val statusName = data[FIELD_STATUS] as? String ?: return null
  val status = TripStatus.entries.firstOrNull { it.name == statusName } ?: return null
  val createdAt = data[FIELD_CREATED_AT].toInstant() ?: return null
  val updatedAt = data[FIELD_UPDATED_AT].toInstant() ?: return null

  if (id.isBlank() || ownerId.isBlank() || !store.isValid() || !handoffLocation.isValid()) {
    return null
  }

  return Trip(
      id = id,
      ownerId = ownerId,
      store = store,
      scheduledAt = scheduledAt,
      handoffLocation = handoffLocation,
      status = status,
      createdAt = createdAt,
      updatedAt = updatedAt,
  )
}

private fun Any?.toLocation(): Location? {
  val data = this as? Map<*, *> ?: return null
  val name = data[FIELD_NAME] as? String ?: return null
  val latitude = (data[FIELD_LATITUDE] as? Number)?.toDouble() ?: return null
  val longitude = (data[FIELD_LONGITUDE] as? Number)?.toDouble() ?: return null
  return Location(name = name, latitude = latitude, longitude = longitude)
}

private fun Location.isValid(): Boolean =
    name.isNotBlank() &&
        latitude.isFinite() &&
        latitude in -90.0..90.0 &&
        longitude.isFinite() &&
        longitude in -180.0..180.0

private fun Any?.toInstant(): Instant? {
  val timestamp = this as? Timestamp ?: return null
  return Instant.ofEpochSecond(timestamp.seconds, timestamp.nanoseconds.toLong())
}

private fun Instant.toTimestamp(): Timestamp = Timestamp(epochSecond, nano)

private fun List<TripFirestoreDocument>.toTripsResult(): TripResult<List<Trip>> {
  val trips = ArrayList<Trip>(size)
  for (document in this) {
    trips += document.toTrip() ?: return TripResult.Error(TripError.InvalidData)
  }
  return TripResult.Success(trips)
}

private suspend fun <T> runRepositoryOperation(
    notFoundError: TripError = TripError.Unknown,
    operation: suspend () -> TripResult<T>,
): TripResult<T> =
    try {
      operation()
    } catch (exception: CancellationException) {
      throw exception
    } catch (exception: Exception) {
      exception.toTripErrorResult(notFoundError)
    }

private fun Exception.toTripErrorResult(notFoundError: TripError): TripResult.Error =
    TripResult.Error(toTripError(notFoundError))

private fun Exception.toTripError(notFoundError: TripError): TripError =
    when (this) {
      is FirebaseFirestoreException ->
          when (code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> TripError.PermissionDenied
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> TripError.NetworkError
            FirebaseFirestoreException.Code.NOT_FOUND -> notFoundError
            FirebaseFirestoreException.Code.INVALID_ARGUMENT,
            FirebaseFirestoreException.Code.OUT_OF_RANGE,
            FirebaseFirestoreException.Code.DATA_LOSS -> TripError.InvalidData
            else -> TripError.Unknown
          }
      is IOException -> TripError.NetworkError
      else -> TripError.Unknown
    }

private const val TRIPS_COLLECTION = "trips"
private const val FIELD_OWNER_ID = "ownerId"
private const val FIELD_STORE = "store"
private const val FIELD_SCHEDULED_AT = "scheduledAt"
private const val FIELD_HANDOFF_LOCATION = "handoffLocation"
private const val FIELD_STATUS = "status"
private const val FIELD_CREATED_AT = "createdAt"
private const val FIELD_UPDATED_AT = "updatedAt"
private const val FIELD_NAME = "name"
private const val FIELD_LATITUDE = "latitude"
private const val FIELD_LONGITUDE = "longitude"
