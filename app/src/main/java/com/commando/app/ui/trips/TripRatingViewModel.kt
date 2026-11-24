package com.commando.app.ui.trips

// AI assistance: OpenAI Codex.
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.commando.app.data.repository.RatingError
import com.commando.app.data.repository.RatingRepository
import com.commando.app.data.repository.RatingResult
import com.commando.app.model.RatingSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Loads the overall rating summary only when [refresh] is called with the loaded trip's owner ID.
 * Construction performs no query. Supply a [repository] bound to the authenticated session.
 *
 * Owner changes cancel previous work without waiting for an uncooperative repository. Cancelled
 * work may finish in the background, but cancellation and request identity prevent stale updates.
 * Scope this model to the Trip Details destination; trip actions remain in [TripDetailsViewModel].
 */
class TripRatingViewModel(private val repository: RatingRepository) : ViewModel() {
  private val mutableState = MutableStateFlow<TripRatingUiState>(TripRatingUiState.Loading)

  /** Observable rating state; only this ViewModel can update it. */
  val uiState: StateFlow<TripRatingUiState> = mutableState.asStateFlow()

  private var commandoId: String? = null
  private var requestJob: Job? = null
  private var generation = 0L

  /**
   * Loads the overall rating summary for [commandoId], which must be the trip owner's ID. Calls for
   * the same owner are ignored while loading; after completion they refresh the summary. A
   * different owner immediately clears previous content and replaces the request without waiting.
   * Identity validation belongs to the repository. Call on the main thread.
   */
  @MainThread
  fun refresh(commandoId: String) {
    if (this.commandoId == commandoId && requestJob?.isActive == true) return
    this.commandoId = commandoId
    val requestGeneration = ++generation
    requestJob?.cancel()
    mutableState.value = TripRatingUiState.Loading

    // Install the job before starting it, including when the repository returns synchronously.
    val job =
        viewModelScope.launch(start = CoroutineStart.LAZY) {
          val result =
              try {
                repository.getRatingSummary(commandoId)
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (_: Exception) {
                RatingResult.Error(RatingError.Unknown)
              }
          coroutineContext.ensureActive()
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
