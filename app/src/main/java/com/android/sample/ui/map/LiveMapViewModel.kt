package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.ui.session.SessionGuard
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One person marker retains every associated trip/order; unavailable tracking stays selectable. */
data class LiveCommando(
    val ownerId: String,
    val trips: List<Trip>,
    val orders: List<Order> = emptyList(),
    val position: CommandoPosition? = null,
)

enum class LiveMapMode {
  DISCOVERY,
  TRACKING,
}

/** Unknown orders never masquerade as an empty order list. */
sealed interface LiveMapState {
  data object Loading : LiveMapState

  data object Unavailable : LiveMapState

  data class Content(
      val mode: LiveMapMode,
      val commandos: List<LiveCommando>,
      val discoveryTrips: List<Trip> = emptyList(),
  ) : LiveMapState
}

/** Composes existing trip/filter state, live orders and one position subscription per commando. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveMapViewModel(
    private val mapTrips: StateFlow<TripMapUiState>,
    private val orders: OrderRepository,
    private val trips: TripRepository,
    private val positions: CommandoPositionRepository,
    private val selectedPoint: StateFlow<Location?>,
    currentUserId: () -> String?,
    sessionChanges: Flow<String?>,
    private val clock: Clock = Clock.systemUTC(),
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
  private val mutableState = MutableStateFlow<LiveMapState>(LiveMapState.Loading)
  val uiState = mutableState.asStateFlow()
  private val session = SessionGuard(currentUserId)
  private val device = MutableStateFlow<DeviceLocation?>(null)
  private var observer: Job? = null

  init {
    viewModelScope.launch { sessionChanges.collect { if (session.hasEnded(it)) stop() } }
  }

  /** Starts only while the map is visible; retry replaces every former subscription. */
  fun start() {
    val previous = observer
    previous?.cancel()
    mutableState.value = LiveMapState.Loading
    if (session.initialUserId == null || session.hasEnded()) return
    observer = viewModelScope.launch {
      previous?.join()
      try {
        val tripChanges =
            mapTrips
                .map { if (it is TripMapUiState.Content) it.copy(selectedTripId = null) else it }
                .distinctUntilChanged()
        combine(tripChanges, orders.observeMyOrders(), ::Pair).collectLatest { (map, result) ->
          val mine = (result as? OrderResult.Success)?.data
          if (mine == null || mine.any { it.requesterId != session.initialUserId }) {
            mutableState.value = LiveMapState.Unavailable
            return@collectLatest
          }
          val active = mine.filter { it.status.isActiveForTracking() }
          val tracking = active.isNotEmpty()
          if (
              !tracking &&
                  (map == TripMapUiState.Loading ||
                      map is TripMapUiState.Error ||
                      map == TripMapUiState.SessionEnded)
          ) {
            mutableState.value =
                if (map == TripMapUiState.Loading) LiveMapState.Loading
                else LiveMapState.Unavailable
            return@collectLatest
          }
          val eligible = (map as? TripMapUiState.Content)?.trips.orEmpty()
          val associated =
              if (tracking)
                  active
                      .map { it.tripId }
                      .distinct()
                      .map { id ->
                        val trip =
                            (trips.getTripById(id) as? TripResult.Success)?.data
                                ?: throw IllegalStateException("Order trip unavailable")
                        check(trip.id == id && trip.ownerId.isNotBlank())
                        trip
                      }
              else eligible.filter { it.ownerId != session.initialUserId }
          currentCoroutineContext().ensureActive()
          if (session.hasEnded()) return@collectLatest
          val people =
              associated
                  .groupBy { it.ownerId }
                  .map { (uid, ownedTrips) ->
                    LiveCommando(
                        uid,
                        ownedTrips.sortedBy { it.id },
                        active
                            .filter { order -> ownedTrips.any { it.id == order.tripId } }
                            .sortedBy { it.id },
                    )
                  }
                  .sortedBy { it.ownerId }
          val updates = people.map { person ->
            positions
                .observe(person.ownerId, person.orders.firstOrNull()?.id)
                .catch { cause ->
                  if (cause is CancellationException) throw cause
                  emit(null)
                }
                .map { person.copy(position = it) }
          }
          val live =
              if (updates.isEmpty()) flowOf(emptyList()) else combine(updates) { it.toList() }
          combine(live, selectedPoint, device, timeWhileVisible()) { commandos, point, fix, now ->
                val origin =
                    fix?.takeIf { it.isUsable(elapsedMillis()) && it.canIdentifyNearbyTrips() }
                        ?.let { Location("", it.latitude, it.longitude) }
                        ?: point?.takeIf { it.hasValidCoordinates() }
                        ?: CAMPUS_DISCOVERY_CENTER
                val currentTrips = eligible.filter {
                  it.status == TripStatus.PUBLISHED && it.scheduledAt > now
                }
                val currentPeople =
                    commandos
                        .map { person ->
                          person.copy(
                              trips =
                                  if (tracking) person.trips
                                  else
                                      person.trips.filter { trip ->
                                        currentTrips.any { it.id == trip.id }
                                      },
                              position = person.position?.takeIf { fix -> fix.isFresh(now) },
                          )
                        }
                        .filter { person ->
                          tracking ||
                              person.position?.let { position ->
                                person.trips.any { trip ->
                                  currentTrips.any { it.id == trip.id }
                                } &&
                                    distanceMeters(
                                        origin,
                                        Location("", position.latitude, position.longitude),
                                    ) <= HANDOFF_RADIUS_METERS
                              } == true
                        }
                LiveMapState.Content(
                    if (tracking) LiveMapMode.TRACKING else LiveMapMode.DISCOVERY,
                    currentPeople,
                    if (tracking) emptyList() else currentTrips,
                )
              }
              .collect {
                currentCoroutineContext().ensureActive()
                if (!session.hasEnded()) mutableState.value = it
              }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.value = LiveMapState.Unavailable
      }
    }
  }

  /** Location never mutates the shared delivery-location selection. */
  fun updateLocation(state: MapLocationUiState) {
    device.value =
        ((state as? MapLocationUiState.Resolved)?.result
                as? com.android.sample.data.location.DeviceLocationResult.Available)
            ?.location
  }

  /** Cancels repository listeners and drops coordinates immediately on screen/session exit. */
  fun stop() {
    observer?.cancel()
    observer = null
    device.value = null
    mutableState.value = LiveMapState.Loading
  }

  private fun timeWhileVisible(): Flow<Instant> = flow {
    while (currentCoroutineContext().isActive) {
      emit(clock.instant())
      delay(LOCAL_REFRESH_MILLIS)
    }
  }

  companion object {
    const val LOCAL_REFRESH_MILLIS = 1_000L
  }
}

/** Used only without a usable device fix or selected delivery point; never replaces that filter. */
internal val CAMPUS_DISCOVERY_CENTER = Location("EPFL", 46.521, 6.566)
