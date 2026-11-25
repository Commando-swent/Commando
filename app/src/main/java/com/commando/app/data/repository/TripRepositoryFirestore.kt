package com.commando.app.data.repository

// AI assistance: OpenAI Codex.
// AI assistance: Claude Code for the trip transitions (#233, #449) and idempotent publication
// (#394).
import com.commando.app.model.Location
import com.commando.app.model.NewTrip
import com.commando.app.model.OrderStatus
import com.commando.app.model.Trip
import com.commando.app.model.TripStatus
import com.commando.app.model.TripTransition
import com.commando.app.model.authentication.AuthRepository
import com.commando.app.model.blocksTripCompletion
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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

  override suspend fun publishTrip(tripId: String, newTrip: NewTrip): TripResult<Trip> {
    val creationTime = now()
    if (!tripId.isDocumentId() || !newTrip.store.isValid() || !newTrip.handoffLocation.isValid()) {
      return TripResult.Error(TripError.InvalidData)
    }

    val ownerId =
        authRepository.currentUser?.uid?.takeIf(String::isNotBlank)
            ?: return TripResult.Error(TripError.PermissionDenied)

    return runRepositoryOperation {
      // A passed departure can still confirm an earlier attempt, but never creates a trip.
      val stored =
          if (newTrip.scheduledAt > creationTime) {
            dataSource.createTripIfAbsent(tripId, newTrip.toFirestoreData(ownerId, creationTime))
          } else {
            dataSource.getTrip(tripId) ?: return@runRepositoryOperation invalidData()
          }
      val trip = stored.toTrip() ?: return@runRepositoryOperation invalidData()
      when {
        trip.ownerId != ownerId -> TripResult.Error(TripError.PermissionDenied)
        !trip.isPublicationOf(newTrip) -> invalidData()
        else -> TripResult.Success(trip)
      }
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

  override fun observeUpcomingTrips(): Flow<TripResult<List<Trip>>?> = flow {
    val uid = authRepository.currentUser?.uid
    if (uid.isNullOrBlank()) {
      emit(TripResult.Error(TripError.PermissionDenied))
      return@flow
    }
    emitAll(
        dataSource
            .observeUpcomingTrips(TripStatus.PUBLISHED.name, now(), uid)
            .map { documents ->
              if (authRepository.currentUser?.uid != uid)
                  TripResult.Error(TripError.PermissionDenied)
              else documents?.toTripsResult()
            }
            .catch { error ->
              if (error is CancellationException || error !is Exception) throw error
              emit(TripResult.Error(error.toFirestoreFailure().toTripError(TripError.Unknown)))
            }
            .expireUpcomingTrips(now)
    )
  }

  override suspend fun getMyTrips(): TripResult<List<Trip>> {
    val ownerId =
        authRepository.currentUser?.uid?.takeIf(String::isNotBlank)
            ?: return TripResult.Error(TripError.PermissionDenied)

    return runRepositoryOperation { dataSource.getTripsByOwner(ownerId).toTripsResult() }
  }

  override suspend fun transitionTrip(
      tripId: String,
      transition: TripTransition,
  ): TripResult<Trip> {
    val uid =
        authRepository.currentUser?.uid?.takeIf(String::isNotBlank)
            ?: return TripResult.Error(TripError.PermissionDenied)
    if (!tripId.isDocumentId()) return TripResult.Error(TripError.InvalidData)

    return runRepositoryOperation(notFoundError = TripError.NotFound) {
      val document = dataSource.getTrip(tripId) ?: return@runRepositoryOperation notFound()
      val trip = document.toTrip() ?: return@runRepositoryOperation invalidData()
      trip.transitionPrecheck(uid, transition)?.let {
        return@runRepositoryOperation it
      }
      if (transition.requiresSettledOrders) {
        openOrdersError(tripId)?.let {
          return@runRepositoryOperation it
        }
      }
      val stored =
          dataSource.transitionTrip(tripId, transition.source.name, transition.target.name)
              ?: return@runRepositoryOperation notFound()
      val updated = stored.document.toTrip() ?: return@runRepositoryOperation invalidData()
      // A change this call wrote is a success even if another device changed the trip right after.
      if (stored.written || updated.status == transition.target) TripResult.Success(updated)
      else TripResult.Error(TripError.InvalidTransition)
    }
  }

  /**
   * InvalidData for an unreadable order status, OrdersStillOpen while an order of [tripId]
   * [blocksTripCompletion], otherwise null. For COMPLETE the trip is already IN_PROGRESS, so no
   * order can be created any more (the rules require PUBLISHED) and this server read cannot go
   * stale. For CANCEL the trip is still PUBLISHED: an order created between this read and the
   * transaction stays PENDING on the cancelled trip, where its requester can still cancel it and
   * the owner can still reject it. Client transactions cannot run queries, so this window remains.
   */
  private suspend fun openOrdersError(tripId: String): TripResult.Error? {
    val statuses = dataSource.getOrderStatuses(tripId).map { it.toOrderStatus() }
    return when {
      null in statuses -> invalidData()
      statuses.any { it?.blocksTripCompletion() == true } ->
          TripResult.Error(TripError.OrdersStillOpen)
      else -> null
    }
  }

  private fun notFound() = TripResult.Error(TripError.NotFound)

  private fun invalidData() = TripResult.Error(TripError.InvalidData)

  private fun String?.toOrderStatus(): OrderStatus? =
      OrderStatus.entries.firstOrNull { it.name == this }
}

/** Raw trip transport; [FirestoreDocument] is decoded with Trip-specific validation below. */
internal typealias TripFirestoreDocument = FirestoreDocument

/**
 * Outcome of [TripFirestoreDataSource.transitionTrip]: [document] is the trip read after the
 * transaction, and [written] is true when that transaction wrote the requested status itself.
 */
internal data class TripTransitionWrite(val document: TripFirestoreDocument, val written: Boolean)

/**
 * SDK-independent trip persistence; typed validation and missing-trip policy stay in the
 * repository.
 */
internal interface TripFirestoreDataSource {
  /**
   * In one server transaction, stores [data] under [tripId] only if no trip exists there, and
   * returns the stored trip: the existing one, unchanged, or the created one. Fails while offline
   * instead of queueing a write.
   */
  suspend fun createTripIfAbsent(tripId: String, data: Map<String, Any?>): TripFirestoreDocument

  /** Uses the SDK default read source; returns null only for a readable missing document. */
  suspend fun getTrip(tripId: String): TripFirestoreDocument?

  /** Filters by status and future departure, ordered by scheduled time ascending. */
  suspend fun getUpcomingTrips(
      status: String,
      scheduledAfter: Instant,
  ): List<TripFirestoreDocument>

  fun observeUpcomingTrips(
      status: String,
      scheduledAfter: Instant,
      uid: String,
  ): Flow<List<TripFirestoreDocument>?> = flow { error("Live trips unsupported") }

  /**
   * Queries persisted owner identity; callers and rules retain responsibility for access control.
   */
  suspend fun getTripsByOwner(ownerId: String): List<TripFirestoreDocument>

  /**
   * Raw `status` of every order whose `tripId` is [tripId], read from the server; null when the
   * field is missing or not a string. The rules allow this query to the trip owner only.
   */
  suspend fun getOrderStatuses(tripId: String): List<String?>

  /**
   * In one transaction, reads trip [tripId] and, only if its stored status is [from], writes status
   * [to] and a server `updatedAt`. Then returns the stored trip read from the server and whether
   * the transaction wrote, or null if the trip does not exist.
   */
  suspend fun transitionTrip(tripId: String, from: String, to: String): TripTransitionWrite?
}

/** Trip SDK adapter; intentionally preserves default-source reads and existing query filters. */
internal class FirebaseTripFirestoreDataSource(private val firestore: FirebaseFirestore) :
    TripFirestoreDataSource {

  private val trips = firestore.collection(TRIPS_COLLECTION)

  // A transaction, unlike set(), is never queued offline, so a failed attempt leaves nothing to
  // replay on reconnection.
  override suspend fun createTripIfAbsent(
      tripId: String,
      data: Map<String, Any?>,
  ): TripFirestoreDocument {
    val reference = trips.document(tripId)
    return firestore
        .runTransaction { transaction ->
          val stored = transaction.get(reference)
          if (stored.exists()) {
            stored.toFirestoreDocument()
          } else {
            transaction.set(reference, data)
            TripFirestoreDocument(tripId, data)
          }
        }
        .await()
  }

  override suspend fun getTrip(tripId: String): TripFirestoreDocument? {
    val snapshot = trips.document(tripId).get().await()
    return snapshot.takeIf { it.exists() }?.let { it.toFirestoreDocument() }
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
          .map { it.toFirestoreDocument() }

  override fun observeUpcomingTrips(status: String, scheduledAfter: Instant, uid: String) =
      trips
          .whereEqualTo(FIELD_STATUS, status)
          .whereGreaterThan(FIELD_SCHEDULED_AT, scheduledAfter.toTimestamp())
          .orderBy(FIELD_SCHEDULED_AT, Query.Direction.ASCENDING)
          .observeServerDocuments(firestore, uid)

  override suspend fun getTripsByOwner(ownerId: String): List<TripFirestoreDocument> =
      trips.whereEqualTo(FIELD_OWNER_ID, ownerId).get().await().documents.map {
        it.toFirestoreDocument()
      }

  override suspend fun getOrderStatuses(tripId: String): List<String?> =
      firestore
          .collection(ORDERS_COLLECTION)
          .whereEqualTo(FIELD_ORDER_TRIP_ID, tripId)
          .get(Source.SERVER)
          .await()
          .documents
          .map { it.get(FIELD_STATUS) as? String }

  override suspend fun transitionTrip(
      tripId: String,
      from: String,
      to: String,
  ): TripTransitionWrite? {
    val reference = trips.document(tripId)
    val written =
        firestore
            .runTransaction { transaction ->
              val snapshot = transaction.get(reference)
              val matches = snapshot.exists() && snapshot.getString(FIELD_STATUS) == from
              if (matches) {
                transaction.update(
                    reference,
                    mapOf(FIELD_STATUS to to, FIELD_UPDATED_AT to FieldValue.serverTimestamp()),
                )
              }
              matches
            }
            .await()
    val stored = reference.get(Source.SERVER).await().takeIf { it.exists() } ?: return null
    return TripTransitionWrite(stored.toFirestoreDocument(), written)
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
  val ownerId = data.field<String>(FIELD_OWNER_ID) ?: return null
  val store = data[FIELD_STORE].toLocation() ?: return null
  val scheduledAt = data[FIELD_SCHEDULED_AT].toInstant() ?: return null
  val handoffLocation = data[FIELD_HANDOFF_LOCATION].toLocation() ?: return null
  val statusName = data.field<String>(FIELD_STATUS) ?: return null
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
    runFirestoreOperation(
        onFailure = { TripResult.Error(it.toTripError(notFoundError)) },
        operation,
    )

private fun FirestoreFailure.toTripError(notFoundError: TripError): TripError =
    when (this) {
      FirestoreFailure.PERMISSION_DENIED,
      FirestoreFailure.UNAUTHENTICATED -> TripError.PermissionDenied
      FirestoreFailure.NETWORK_UNAVAILABLE -> TripError.NetworkError
      FirestoreFailure.NOT_FOUND -> notFoundError
      FirestoreFailure.INVALID_DATA -> TripError.InvalidData
      FirestoreFailure.UNKNOWN -> TripError.Unknown
    }

private const val TRIPS_COLLECTION = "trips"
private const val ORDERS_COLLECTION = "orders"
private const val FIELD_ORDER_TRIP_ID = "tripId"
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
