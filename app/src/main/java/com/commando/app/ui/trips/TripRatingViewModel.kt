package com.commando.app.ui.trips

// AI assistance: OpenAI Codex.
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.commando.app.data.repository.RatingError
import com.commando.app.data.repository.RatingRepository
import com.commando.app.data.repository.RatingResult
import com.commando.app.model.RatingSummary
import com.commando.app.model.ReliabilitySummary
import com.commando.app.ui.session.SessionGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** Rating-section state, independent of trip loading and lifecycle actions. */
sealed class TripRatingUiState {
  /** Waiting for an explicit refresh, or loading the selected owner's summary. */
  data object Loading : TripRatingUiState()

  /** The selected owner's overall summary, with its unrounded average and total count. */
  data class Content(val summary: RatingSummary) : TripRatingUiState()

  /** The selected owner has no ratings; there is no zero-star average. */
  data object Empty : TripRatingUiState()

  /** Loading failed; the rest of Trip Details can remain usable. */
  data class Error(val error: RatingError) : TripRatingUiState()
}

/**
 * Loads stars and reliability independently for the loaded trip's owner via [refreshAll].
 * Construction performs no query. Supply a [repository] bound to the authenticated session;
 * [currentUserId] and [sessionChanges] invalidate both states when that session ends.
 *
 * Owner changes cancel previous work without waiting for an uncooperative repository. Cancelled
 * work may finish in the background, but cancellation and request identity prevent stale updates.
 * Scope this model to the Trip Details destination; trip actions remain in [TripDetailsViewModel].
 */
class TripRatingViewModel(
    private val repository: RatingRepository,
    currentUserId: (() -> String?)? = null,
    sessionChanges: Flow<String?> = emptyFlow(),
) : ViewModel() {
  private val mutableState = MutableStateFlow<TripRatingUiState>(TripRatingUiState.Loading)

  /** Observable rating state; only this ViewModel can update it. */
  val uiState: StateFlow<TripRatingUiState> = mutableState.asStateFlow()

  private var commandoId: String? = null
  private var requestJob: Job? = null
  private var generation = 0L
  private val session = currentUserId?.let(::SessionGuard)
  private val mutableReliability = MutableStateFlow<RatingResult<ReliabilitySummary>?>(null)
  /** Null means loading; success includes confirmed zero history, and failure offers retry. */
  val reliabilityState = mutableReliability.asStateFlow()
  private var reliabilityJob: Job? = null
  private var reliabilityGeneration = 0L

  init {
    viewModelScope.launch {
      sessionChanges.collect { uid -> if (session?.hasEnded(uid) == true) clearSession() }
    }
  }

  private fun clearSession() {
    generation++
    reliabilityGeneration++
    requestJob?.cancel()
    reliabilityJob?.cancel()
    mutableState.value = TripRatingUiState.Error(RatingError.NotAuthenticated)
    mutableReliability.value = RatingResult.Error(RatingError.NotAuthenticated)
  }

  private fun selectOwner(id: String): Boolean {
    if (session?.hasEnded() == true) {
      clearSession()
      return false
    }
    if (commandoId != id) {
      generation++
      reliabilityGeneration++
      requestJob?.cancel()
      reliabilityJob?.cancel()
      mutableState.value = TripRatingUiState.Loading
      mutableReliability.value = null
      commandoId = id
    }
    return true
  }

  /** Loads both independent summaries on destination resume, without waiting for either read. */
  @MainThread
  fun refreshAll(commandoId: String) {
    refresh(commandoId)
    refreshReliability(commandoId)
  }

  /** Refreshes only reliability; overlapping same-owner reads are deduplicated. */
  @MainThread
  fun refreshReliability(commandoId: String) {
    if (!selectOwner(commandoId) || reliabilityJob?.isActive == true) return
    val requested = ++reliabilityGeneration
    mutableReliability.value = null
    reliabilityJob =
        viewModelScope.launch(start = CoroutineStart.LAZY) {
          val result = read { repository.getReliabilitySummary(commandoId) }
          coroutineContext.ensureActive()
          if (session?.hasEnded() == true) clearSession()
          else if (requested == reliabilityGeneration) {
            mutableReliability.value =
                if (result is RatingResult.Success && result.data.commandoId != commandoId)
                    RatingResult.Error(RatingError.InvalidData)
                else result
          }
        }
    reliabilityJob?.start()
  }

  /** Retries reliability for the selected owner without refreshing stars or trip details. */
  @MainThread
  fun retryReliability() {
    commandoId?.let(::refreshReliability)
  }

  private suspend fun <T> read(action: suspend () -> RatingResult<T>): RatingResult<T> =
      try {
        action()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        RatingResult.Error(RatingError.Unknown)
      }

  /**
   * Loads the overall rating summary for [commandoId], which must be the trip owner's ID. Calls for
   * the same owner are ignored while loading; after completion they refresh the summary. A
   * different owner immediately clears previous content and replaces the request without waiting.
   * Identity validation belongs to the repository. Call on the main thread.
   */
  @MainThread
  fun refresh(commandoId: String) {
    if (!selectOwner(commandoId)) return
    if (this.commandoId == commandoId && requestJob?.isActive == true) return
    val requestGeneration = ++generation
    requestJob?.cancel()
    mutableState.value = TripRatingUiState.Loading

    // Install the job before starting it, including when the repository returns synchronously.
    val job =
        viewModelScope.launch(start = CoroutineStart.LAZY) {
          val result = read { repository.getRatingSummary(commandoId) }
          coroutineContext.ensureActive()
          if (session?.hasEnded() == true) {
            clearSession()
            return@launch
          }
          if (requestGeneration != generation) return@launch
          mutableState.value =
              when (result) {
                is RatingResult.Success -> {
                  val summary = result.data
                  when {
                    summary.commandoId != commandoId ->
                        TripRatingUiState.Error(RatingError.InvalidData)
                    summary.ratingCount == 0 -> TripRatingUiState.Empty
                    else -> TripRatingUiState.Content(summary)
                  }
                }
                is RatingResult.Error -> TripRatingUiState.Error(result.error)
              }
        }
    requestJob = job
    job.start()
  }

  /**
   * Reloads the last requested owner; does nothing before the first [refresh] or while that owner's
   * request is active. Cancellation leaves subsequent retries available. Call on the main thread.
   */
  @MainThread
  fun retry() {
    commandoId?.let(::refresh)
  }
}
