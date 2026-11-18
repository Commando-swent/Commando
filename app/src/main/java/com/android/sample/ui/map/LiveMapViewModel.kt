package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.*
import com.android.sample.ui.session.SessionGuard
import com.commando.app.model.*
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One person retains all associated trips/orders even when its position cannot be displayed. */
data class LiveCommando(
    val ownerId: String,
    val trips: List<Trip>,
    val orders: List<Order> = emptyList(),
    val position: CommandoPosition? = null,
    val positionUnavailable: Boolean = false,
)

/** Discovery is entered only after a confirmed empty active-order set. */
enum class LiveMapMode {
  DISCOVERY,
  TRACKING,
}

/** Loading/unconfirmed data and failures never imply discovery. */
sealed interface LiveMapState {
  data object Loading : LiveMapState

  data object Unavailable : LiveMapState

  data class Content(
      val mode: LiveMapMode,
      val commandos: List<LiveCommando>,
      val criteriaProblem: CriteriaProblem? = null,
  ) : LiveMapState
}

/** Session-bound live map state; the screen owner starts/stops it with foreground visibility. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveMapViewModel(
    private val orders: OrderRepository,
    private val trips: TripRepository,
    private val positions: CommandoPositionRepository,
    currentUserId: () -> String?,
    sessionChanges: Flow<String?>,
    private val selectedPoint: StateFlow<Location?> = MutableStateFlow(null),
    private val criteria: StateFlow<TripSearchCriteria> = MutableStateFlow(TripSearchCriteria()),
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
  private val session = SessionGuard(currentUserId)
  private val mutableState = MutableStateFlow<LiveMapState>(LiveMapState.Loading)
  val uiState = mutableState.asStateFlow()
  private var observer: Job? = null
  private var generation = 0L

  init {
    viewModelScope.launch { sessionChanges.collect { if (session.hasEnded(it)) stop() } }
  }

  /** Retry replaces every subscription. An ended session can never restart this instance. */
  fun start() {
    val previous = observer
    stop()
    if (session.initialUserId == null || session.hasEnded()) return
    val epoch = generation
    observer = viewModelScope.launch {
      previous?.join()
      try {
        orders.observeMyOrders().collectLatest { result ->
          show(LiveMapState.Loading, epoch)
          when (result) {
            null -> Unit
            is OrderResult.Error -> show(LiveMapState.Unavailable, epoch)
            is OrderResult.Success -> {
              check(result.data.all { it.requesterId == session.initialUserId })
              val active = result.data.filter { it.status.isActiveForTracking() }
              if (active.isEmpty()) discover(epoch) else track(active, epoch)
            }
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        show(LiveMapState.Unavailable, epoch)
      }
    }
  }

  private suspend fun discover(epoch: Long) {
    trips.observeUpcomingTrips().collectLatest { result ->
      when (result) {
        null -> show(LiveMapState.Loading, epoch)
        is TripResult.Error -> show(LiveMapState.Unavailable, epoch)
        is TripResult.Success ->
            combine(selectedPoint, criteria, ticks()) { point, filters, now ->
                  val eligible =
                      result.data.filter {
                        it.status == TripStatus.PUBLISHED &&
                            it.scheduledAt > now &&
                            it.ownerId != session.initialUserId
                      }
                  DiscoverySelection(
                      searchTrips(eligible, point, filters, now, clock.zone),
                      criteriaProblem(filters, now, clock.zone),
                  )
                }
                .distinctUntilChanged()
                .collectLatest { selection ->
                  val people =
                      selection.trips
                          .groupBy { it.ownerId }
                          .map { (uid, owned) -> LiveCommando(uid, owned) }
                  observePeople(people, LiveMapMode.DISCOVERY, epoch, selection.problem)
                }
      }
    }
  }

  private suspend fun track(active: List<Order>, epoch: Long) {
    val associated =
        active
            .map { it.tripId }
            .distinct()
            .map { id ->
              val trip = (trips.getTripById(id) as? TripResult.Success)?.data
              check(
                  trip?.id == id &&
                      trip.ownerId.isNotBlank() &&
                      trip.ownerId != session.initialUserId
              )
              trip
            }
    val people =
        associated
            .groupBy { it.ownerId }
            .map { (uid, owned) ->
              LiveCommando(
                  uid,
                  owned,
                  active.filter { order -> owned.any { it.id == order.tripId } }.sortedBy { it.id },
              )
            }
    observePeople(people, LiveMapMode.TRACKING, epoch)
  }

  private suspend fun observePeople(
      people: List<LiveCommando>,
      mode: LiveMapMode,
      epoch: Long,
      problem: CriteriaProblem? = null,
  ) {
    val updates =
        people
            .sortedBy { it.ownerId }
            .map { person ->
              val fixes =
                  if (mode == LiveMapMode.TRACKING) trackingFixes(person)
                  else {
                    val streams =
                        person.trips.map {
                          positions.observeDiscovery(person.ownerId, it.id).catch {
                            emitAll(flowOf<CommandoPosition?>(null))
                          }
                        }
                    combine(streams) { values ->
                      values.filterNotNull().maxByOrNull { it.capturedAt }
                    }
                  }
              fixes
                  .map { person.copy(position = it) }
                  .onStart { emit(person) }
                  .catch { emit(person.copy(positionUnavailable = true)) }
            }
    val live = if (updates.isEmpty()) flowOf(emptyList()) else combine(updates) { it.toList() }
    combine(live, selectedPoint, ticks()) { commandos, point, now ->
          val origin = point?.takeIf { it.hasValidCoordinates() } ?: CAMPUS_DISCOVERY_CENTER
          val current =
              commandos
                  .map { it.copy(position = it.position?.takeIf { fix -> fix.isFresh(now) }) }
                  .filter { person ->
                    mode == LiveMapMode.TRACKING ||
                        person.position?.let {
                          distanceMeters(origin, Location("", it.latitude, it.longitude)) <=
                              HANDOFF_RADIUS_METERS
                        } == true
                  }
          LiveMapState.Content(mode, current, problem)
        }
        .collect { show(it, epoch) }
  }

  // The S5 rules authorize the exact shared trip. Try each associated witness, keeping every link.
  private fun trackingFixes(person: LiveCommando): Flow<CommandoPosition?> =
      person.orders
          .distinctBy { it.tripId }
          .asReversed()
          .fold(flow<CommandoPosition?> { error("No authorized position witness") }) {
              fallback,
              order ->
            flow { emitAll(positions.observe(person.ownerId, order.id)) }
                .catch { emitAll(fallback) }
          }

  private suspend fun show(state: LiveMapState, epoch: Long) {
    currentCoroutineContext().ensureActive()
    if (generation == epoch && !session.hasEnded()) mutableState.value = state
  }

  /**
   * Drops coordinates immediately and cancels all child listeners on map/background/session exit.
   */
  fun stop() {
    generation++
    observer?.cancel()
    observer = null
    mutableState.value = LiveMapState.Loading
  }

  private fun ticks(): Flow<Instant> = flow {
    while (currentCoroutineContext().isActive) {
      emit(clock.instant())
      delay(LOCAL_REFRESH_MILLIS)
    }
  }

  private data class DiscoverySelection(val trips: List<Trip>, val problem: CriteriaProblem?)

  companion object {
    const val LOCAL_REFRESH_MILLIS = 1_000L
  }
}

/** Campus fallback without a selected delivery point; uses the shared 500 m radius. */
internal val CAMPUS_DISCOVERY_CENTER = Location("EPFL", 46.521, 6.566)
