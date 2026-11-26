package com.commando.app.ui.trips

// AI assistance: Claude Code for the close and complete actions (#233).
// AI assistance: OpenAI Codex for public-name lookup and refresh.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.commando.app.data.cache.SessionCache
import com.commando.app.data.repository.PublicProfileRepository
import com.commando.app.data.repository.TripError
import com.commando.app.data.repository.TripRepository
import com.commando.app.data.repository.TripResult
import com.commando.app.model.Trip
import com.commando.app.model.TripTransition
import com.commando.app.ui.session.SessionGuard
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

sealed class TripDetailsUiState {
  data object Loading : TripDetailsUiState()

  /**
   * Loaded [trip]. [pendingTransition] waits for the owner's confirmation, [transitionInProgress]
   * is true while it is written, and [transitionError] is the last failed change.
   */
  data class Content(
      val trip: Trip,
      val pendingTransition: TripTransition? = null,
      val transitionInProgress: Boolean = false,
      val transitionError: TripError? = null,
      val cachedAt: Instant? = null,
  ) : TripDetailsUiState()

  data class Error(val error: TripError, val offlineUnavailable: Boolean = false) :
      TripDetailsUiState()
}

/**
 * Loads the supplied trip ID and displays repository results unchanged. The owner can also close or
 * complete the trip; the repository and the rules check ownership and the stored status again.
 */
class TripDetailsViewModel(
    private val repository: TripRepository,
    private val tripId: String,
    private val publicProfiles: Lazy<PublicProfileRepository>? = null,
    currentUserId: (() -> String?)? = null,
    sessionChanges: Flow<String?> = emptyFlow(),
    private val cache: SessionCache? = null,
    private val online: StateFlow<Boolean>? = null,
) : ViewModel() {
  private val mutableState = MutableStateFlow<TripDetailsUiState>(TripDetailsUiState.Loading)
  val uiState: StateFlow<TripDetailsUiState> = mutableState.asStateFlow()
  private var loadJob: Job? = null
  private val key = SessionCache.key<Trip>("trip-details/$tripId")
  private val session = currentUserId?.let(::SessionGuard)
  private val mutablePublicName = MutableStateFlow<Pair<String, String?>?>(null)
  /** Owner UID paired with its public name; null or a missing name uses the screen's fallback. */
  val publicName = mutablePublicName.asStateFlow()
  private var publicNameJob: Job? = null

  init {
    viewModelScope.launch {
      sessionChanges.collect { uid ->
        if (session?.hasEnded(uid) == true) {
          loadJob?.cancel()
          mutableState.value = TripDetailsUiState.Error(TripError.PermissionDenied)
          publicNameJob?.cancel()
          mutablePublicName.value = null
        }
      }
    }
    online?.let {
      viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        it.drop(1).collect { loadTrip() }
      }
    }
    loadTrip()
  }

  /**
   * Refreshes only the loaded owner's public name on Trip Details resume. Construction performs no
   * profile query, so Review Order can reuse this ViewModel. Overlapping same-owner calls
   * deduplicate; owner changes, cancellation and session changes discard late results independently
   * of ratings. A same-owner refresh keeps the confirmed name on failure; a successful result,
   * including null, replaces it.
   */
  fun refreshPublicName(ownerId: String?) {
    val profiles = publicProfiles ?: return
    if (sessionEnded()) {
      publicNameJob?.cancel()
      mutablePublicName.value = null
      return
    }
    if (
        ownerId == null ||
            (mutableState.value as? TripDetailsUiState.Content)?.trip?.ownerId != ownerId
    ) {
      publicNameJob?.cancel()
      mutablePublicName.value = null
      return
    }
    if (mutablePublicName.value?.first == ownerId && publicNameJob?.isActive == true) return
    publicNameJob?.cancel()
    if (mutablePublicName.value?.first != ownerId) {
      mutablePublicName.value = ownerId to null
    }
    val job =
        viewModelScope.launch(start = CoroutineStart.LAZY) {
          val name =
              try {
                profiles.value.getDisplayName(ownerId)
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (_: Exception) {
                return@launch
              }
          ensureActive()
          if (
              session?.hasEnded() != true &&
                  (mutableState.value as? TripDetailsUiState.Content)?.trip?.ownerId == ownerId
          ) {
            mutablePublicName.value = ownerId to name
          }
        }
    publicNameJob = job
    job.start()
  }

  private fun sessionEnded(): Boolean = session?.hasEnded() == true

  private fun allowsTransition(content: TripDetailsUiState.Content): Boolean =
      !sessionEnded() && online?.value != false && content.cachedAt == null

  /** Reloads the trip, e.g. after an error. */
  fun retry() {
    loadTrip()
  }

  /** Asks to confirm [transition]; ignored while another change is being written. */
  fun requestTransition(transition: TripTransition) {
    val content = mutableState.value as? TripDetailsUiState.Content ?: return
    if (!allowsTransition(content) || content.transitionInProgress) return
    mutableState.value = content.copy(pendingTransition = transition, transitionError = null)
  }

  /** Closes the confirmation without changing the trip. */
  fun dismissTransition() {
    val content = mutableState.value as? TripDetailsUiState.Content ?: return
    mutableState.value = content.copy(pendingTransition = null)
  }

  /** Writes the confirmed transition; on success the stored trip replaces the displayed one. */
  fun confirmTransition() {
    val content = mutableState.value as? TripDetailsUiState.Content ?: return
    if (!allowsTransition(content)) return
    val transition = content.pendingTransition ?: return
    mutableState.value = content.copy(pendingTransition = null, transitionInProgress = true)
    viewModelScope.launch {
      val result =
          try {
            repository.transitionTrip(tripId, transition)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            TripResult.Error(TripError.Unknown)
          }
      // A reload started meanwhile owns the state and shows the stored trip itself.
      cache?.remove(key)
      if (sessionEnded()) return@launch
      if (online?.value == false) {
        loadTrip()
        return@launch
      }
      val current = mutableState.value as? TripDetailsUiState.Content ?: return@launch
      mutableState.value =
          when (result) {
            is TripResult.Success -> confirmed(result.data)
            is TripResult.Error ->
                current.copy(transitionInProgress = false, transitionError = result.error)
          }
    }
  }

  private fun confirmed(trip: Trip): TripDetailsUiState =
      if (cache?.put(key, trip) == false) TripDetailsUiState.Error(TripError.PermissionDenied)
      else TripDetailsUiState.Content(trip)

  private fun fallback(error: TripError) =
      cache?.get(key)?.let { TripDetailsUiState.Content(it.value, cachedAt = it.confirmedAt) }
          ?: TripDetailsUiState.Error(error, offlineUnavailable = online?.value == false)

  private fun loadTrip() {
    if (sessionEnded()) return
    if (online?.value == false) {
      loadJob?.cancel()
      mutableState.value = fallback(TripError.NetworkError)
      return
    }
    if (loadJob?.isActive == true) return
    mutableState.value = TripDetailsUiState.Loading
    loadJob = viewModelScope.launch {
      val result =
          try {
            repository.getTripById(tripId)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            TripResult.Error(TripError.Unknown)
          }
      ensureActive()
      if (sessionEnded()) return@launch
      mutableState.value =
          when (result) {
            is TripResult.Success -> confirmed(result.data)
            is TripResult.Error ->
                when (result.error) {
                  TripError.NetworkError,
                  TripError.Unknown -> fallback(result.error)
                  else -> {
                    cache?.remove(key)
                    TripDetailsUiState.Error(result.error)
                  }
                }
          }
    }
  }
}
