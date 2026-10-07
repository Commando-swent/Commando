package com.android.sample.ui.home

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HomeTripUiState {
  data object Loading : HomeTripUiState

  data object Empty : HomeTripUiState

  data class Content(val trip: Trip) : HomeTripUiState

  data class Error(val error: TripError) : HomeTripUiState
}

/** Loads the signed-in commando's current trip when Home becomes visible. */
class HomeViewModel(
    private val repository: TripRepository,
    private val userId: String,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {
  private val mutableState = MutableStateFlow<HomeTripUiState>(HomeTripUiState.Loading)
  val uiState = mutableState.asStateFlow()
  private var isLoading = false

  fun refresh() {
    if (isLoading) return
    isLoading = true
    mutableState.value = HomeTripUiState.Loading
    viewModelScope.launch {
      try {
        mutableState.value =
            when (val result = repository.getMyTrips()) {
              is TripResult.Error -> HomeTripUiState.Error(result.error)
              is TripResult.Success -> {
                val currentTime = now()
                val ownTrips = result.data.filter { it.ownerId == userId }
                // An ongoing trip takes priority over the next scheduled departure.
                val current =
                    ownTrips
                        .filter { it.status == TripStatus.IN_PROGRESS }
                        .minWithOrNull(compareBy(Trip::scheduledAt, Trip::id))
                        ?: ownTrips
                            .filter {
                              it.status == TripStatus.PUBLISHED && it.scheduledAt >= currentTime
                            }
                            .minWithOrNull(compareBy(Trip::scheduledAt, Trip::id))
                current?.let(HomeTripUiState::Content) ?: HomeTripUiState.Empty
              }
            }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        mutableState.value = HomeTripUiState.Error(TripError.Unknown)
      } finally {
        isLoading = false
      }
    }
  }
}
