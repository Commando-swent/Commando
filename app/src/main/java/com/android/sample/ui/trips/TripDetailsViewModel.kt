package com.android.sample.ui.trips

// AI assistance: Claude Code for the close and complete actions (#233).
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Trip
import com.android.sample.model.TripTransition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
  ) : TripDetailsUiState()

  data class Error(val error: TripError) : TripDetailsUiState()
}

/**
 * Loads the supplied trip ID and displays repository results unchanged. The owner can also close or
 * complete the trip; the repository and the rules check ownership and the stored status again.
 */
class TripDetailsViewModel(
    private val repository: TripRepository,
    private val tripId: String,
) : ViewModel() {
  private val mutableState = MutableStateFlow<TripDetailsUiState>(TripDetailsUiState.Loading)
  val uiState: StateFlow<TripDetailsUiState> = mutableState.asStateFlow()
  private var isLoading = false

  init {
    loadTrip()
  }

  /** Reloads the trip, e.g. after an error. */
  fun retry() {
    loadTrip()
  }

  /** Asks to confirm [transition]; ignored while another change is being written. */
  fun requestTransition(transition: TripTransition) {
    val content = mutableState.value as? TripDetailsUiState.Content ?: return
    if (content.transitionInProgress) return
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
      val current = mutableState.value as? TripDetailsUiState.Content ?: return@launch
      mutableState.value =
          when (result) {
            is TripResult.Success -> TripDetailsUiState.Content(result.data)
            is TripResult.Error ->
                current.copy(transitionInProgress = false, transitionError = result.error)
          }
    }
  }

  private fun loadTrip() {
    if (isLoading) return
    // Set this before launching so repeated calls cannot start concurrent requests.
    isLoading = true
    mutableState.value = TripDetailsUiState.Loading
    viewModelScope.launch {
      try {
        mutableState.value =
            when (val result = repository.getTripById(tripId)) {
              is TripResult.Success -> TripDetailsUiState.Content(result.data)
              is TripResult.Error -> TripDetailsUiState.Error(result.error)
            }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        mutableState.value = TripDetailsUiState.Error(TripError.Unknown)
      } finally {
        isLoading = false
      }
    }
  }
}
