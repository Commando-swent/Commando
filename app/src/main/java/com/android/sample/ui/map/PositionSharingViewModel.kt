package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.location.*
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.ui.session.SessionGuard
import java.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground sharing is explicit; failures are user-visible and cleanup precedes normal sign-out.
 */
sealed interface PositionSharingState {
  data object Off : PositionSharingState

  data object Starting : PositionSharingState

  data object Live : PositionSharingState

  data object Stopping : PositionSharingState

  data class Failed(val reason: SharingFailureReason) : PositionSharingState
}

/** Localized by the UI; never expose platform/backend exception messages to users. */
enum class SharingFailureReason {
  PERMISSION,
  LOCATION,
  PUBLISH,
  STOP,
  NO_TRIP,
}

/**
 * Publishes only with explicit consent. Eligibility is rechecked each publication cycle; failures
 * stop sharing until the user retries. [createRepository] is lazy to avoid pre-consent backend
 * work. [trips] supplies owned trips, [provider] supplies fixes and session inputs prevent stale
 * uploads.
 */
class PositionSharingViewModel(
    createRepository: () -> CommandoPositionRepository,
    private val trips: TripRepository,
    private val provider: DeviceLocationProvider,
    currentUserId: () -> String?,
    sessionChanges: Flow<String?>,
    private val clock: Clock = Clock.systemUTC(),
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
  private val repository: CommandoPositionRepository by lazy(createRepository)
  private val mutableState = MutableStateFlow<PositionSharingState>(PositionSharingState.Off)
  val uiState = mutableState.asStateFlow()
  private val session = SessionGuard(currentUserId)
  private val transitions = Mutex()
  private var worker: Job? = null
  private var dirty = false

  init {
    viewModelScope.launch { sessionChanges.collect { if (session.hasEnded(it)) stop().await() } }
  }

  /** Only the permission host's explicit successful opt-in invokes this method. */
  fun start(permissionGranted: Boolean) {
    viewModelScope.launch {
      transitions.withLock {
        if (session.hasEnded() || session.initialUserId == null) return@withLock
        worker?.cancelAndJoin()
        worker = null
        if (!permissionGranted) {
          if (dirty && !cleanup()) return@withLock
          mutableState.value = PositionSharingState.Failed(SharingFailureReason.PERMISSION)
          return@withLock
        }
        mutableState.value = PositionSharingState.Starting
        worker = viewModelScope.launch { publishWhileActive() }
      }
    }
  }

  /** Returns a ViewModel-owned stop operation; await confirmation before normal sign-out. */
  fun stop(): Deferred<Boolean> = viewModelScope.async {
    transitions.withLock {
      worker?.cancelAndJoin()
      worker = null
      if (dirty) cleanup()
      else {
        mutableState.value = PositionSharingState.Off
        true
      }
    }
  }

  private suspend fun publishWhileActive() {
    var failure: SharingFailureReason? = null
    try {
      while (currentCoroutineContext().isActive) {
        val owned =
            (trips.getMyTrips() as? TripResult.Success)?.data
                ?: throw SharingFailure(SharingFailureReason.PUBLISH)
        val mine = owned.filter { it.ownerId == session.initialUserId }
        val trip =
            mine.firstOrNull { it.status == TripStatus.IN_PROGRESS }
                ?: mine
                    .filter {
                      it.status == TripStatus.PUBLISHED && it.scheduledAt > clock.instant()
                    }
                    .minByOrNull { it.scheduledAt }
                ?: throw SharingFailure(SharingFailureReason.NO_TRIP)
        publishPosition(trip)
        delay(PUBLICATION_INTERVAL_MILLIS)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: SharingFailure) {
      failure = error.reason
    } catch (_: Exception) {
      failure = SharingFailureReason.PUBLISH
    } finally {
      withContext(NonCancellable) {
        val deleted = cleanup()
        if (deleted) failure?.let { mutableState.value = PositionSharingState.Failed(it) }
      }
    }
  }

  private suspend fun publishPosition(trip: Trip) {
    val result =
        withTimeoutOrNull(MapLocationViewModel.REQUEST_TIMEOUT_MILLIS) {
          provider.currentLocation()
        }
    if (result == DeviceLocationResult.PermissionDenied)
        throw SharingFailure(SharingFailureReason.PERMISSION)
    val fix =
        (result as? DeviceLocationResult.Available)?.location
            ?: throw SharingFailure(SharingFailureReason.LOCATION)
    val now = elapsedMillis()
    if (!fix.isUsable(now) || now - fix.elapsedRealtimeMillis >= DeviceLocation.MAX_AGE_MILLIS)
        throw SharingFailure(SharingFailureReason.LOCATION)
    currentCoroutineContext().ensureActive()
    if (session.hasEnded()) throw CancellationException("Sharing session ended")
    // A monotonic fix time belongs only to this boot; persist its original UTC capture age.
    val position =
        CommandoPosition(
            fix.latitude,
            fix.longitude,
            fix.accuracyMeters.toDouble(),
            clock.instant().minusMillis(now - fix.elapsedRealtimeMillis),
        )
    dirty = true
    repository.publish(trip.id, position)
    currentCoroutineContext().ensureActive()
    mutableState.value = PositionSharingState.Live
  }

  private suspend fun cleanup(): Boolean {
    if (!dirty) {
      mutableState.value = PositionSharingState.Off
      return true
    }
    mutableState.value = PositionSharingState.Stopping
    return try {
      withTimeout(DELETE_TIMEOUT_MILLIS) { repository.stopSharing() }
      dirty = false
      mutableState.value = PositionSharingState.Off
      true
    } catch (_: Exception) {
      currentCoroutineContext().ensureActive()
      mutableState.value = PositionSharingState.Failed(SharingFailureReason.STOP)
      false
    }
  }

  private class SharingFailure(val reason: SharingFailureReason) : Exception()

  companion object {
    const val PUBLICATION_INTERVAL_MILLIS = 10_000L
    const val DELETE_TIMEOUT_MILLIS = 5_000L
  }
}
