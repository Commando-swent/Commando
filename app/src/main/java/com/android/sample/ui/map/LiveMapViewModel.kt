package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
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
) : ViewModel() {
  private val mutableState = MutableStateFlow<LiveMapState>(LiveMapState.Loading)
  val uiState = mutableState.asStateFlow()
  private val session = SessionGuard(currentUserId)
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
          observeMap(map, result)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.value = LiveMapState.Unavailable
      }
    }
  }

  private suspend fun observeMap(map: TripMapUiState, result: OrderResult<List<Order>>) {
    val mine = (result as? OrderResult.Success)?.data
    if (mine == null || mine.any { it.requesterId != session.initialUserId }) {
      mutableState.value = LiveMapState.Unavailable
      return
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
          if (map == TripMapUiState.Loading) LiveMapState.Loading else LiveMapState.Unavailable
      return
    }
    val eligible = (map as? TripMapUiState.Content)?.trips.orEmpty()
    val associated = associatedTrips(active, eligible)
    currentCoroutineContext().ensureActive()
    if (session.hasEnded()) return
    val people =
        associated
            .groupBy { it.ownerId }
            .map { (uid, owned) ->
              val ids = owned.mapTo(hashSetOf()) { it.id }
              val associatedOrders = active.filter { it.tripId in ids }.sortedBy { it.id }
              LiveCommando(uid, owned.sortedBy { it.id }, associatedOrders)
            }
            .sortedBy { it.ownerId }
    val updates = people.map(::observePerson)
    val live = if (updates.isEmpty()) flowOf(emptyList()) else combine(updates) { it.toList() }
    combine(live, selectedPoint, timeWhileVisible()) { commandos, point, now ->
          render(commandos, eligible, tracking, point, now)
        }
        .collect {
          currentCoroutineContext().ensureActive()
          if (!session.hasEnded()) mutableState.value = it
        }
  }

  private suspend fun associatedTrips(active: List<Order>, eligible: List<Trip>): List<Trip> {
    if (active.isEmpty()) return eligible.filter { it.ownerId != session.initialUserId }
    return active
        .map { it.tripId }
        .distinct()
        .map { id ->
          val trip =
              (trips.getTripById(id) as? TripResult.Success)?.data
                  ?: throw IllegalStateException("Order trip unavailable")
          check(trip.id == id && trip.ownerId.isNotBlank())
          trip
        }
  }

  private fun observePerson(person: LiveCommando) =
      positions
          .observe(person.ownerId, person.orders.firstOrNull()?.id)
          .catch { cause ->
            if (cause is CancellationException) throw cause
            emit(null)
          }
          .map { person.copy(position = it) }

  private fun render(
      commandos: List<LiveCommando>,
      eligible: List<Trip>,
      tracking: Boolean,
      point: Location?,
      now: Instant,
  ): LiveMapState.Content {
    val origin = point?.takeIf { it.hasValidCoordinates() } ?: CAMPUS_DISCOVERY_CENTER
    val currentTrips = eligible.filter { it.status == TripStatus.PUBLISHED && it.scheduledAt > now }
    val ids = currentTrips.mapTo(hashSetOf()) { it.id }
    val people =
        commandos
            .map { person ->
              person.copy(
                  trips = if (tracking) person.trips else person.trips.filter { it.id in ids },
                  position = person.position?.takeIf { it.isFresh(now) },
              )
            }
            .filter { person ->
              tracking ||
                  person.position?.let { position ->
                    person.trips.isNotEmpty() &&
                        distanceMeters(
                            origin,
                            Location("", position.latitude, position.longitude),
                        ) <= HANDOFF_RADIUS_METERS
                  } == true
            }
    return LiveMapState.Content(
        if (tracking) LiveMapMode.TRACKING else LiveMapMode.DISCOVERY,
        people,
        if (tracking) emptyList() else currentTrips,
    )
  }

  /** Cancels repository listeners and drops coordinates immediately on screen/session exit. */
  fun stop() {
    observer?.cancel()
    observer = null
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

/** Fallback when no valid delivery point is selected; never replaces that filter. */
internal val CAMPUS_DISCOVERY_CENTER = Location("EPFL", 46.521, 6.566)
