package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.SavedTripSearchRepository
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripResult
import com.android.sample.model.SavedTripSearch
import com.android.sample.model.TripSearchCriteria
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The user's presets; [error] reports the last failed load or change and keeps the list. */
data class SavedTripSearchUiState(
    val searches: List<SavedTripSearch> = emptyList(),
    val isLoading: Boolean = false,
    val error: TripError? = null,
)

/**
 * Saves, renames and deletes the current user's presets. Applying one is done by the owner of the
 * search criteria (the session [TripLocationFilterViewModel]), so the delivery point is untouched.
 * Scope it per account, like the filter: a new account never sees the previous account's list.
 */
class SavedTripSearchViewModel(private val repository: SavedTripSearchRepository) : ViewModel() {
  private val mutableState = MutableStateFlow(SavedTripSearchUiState())
  val uiState: StateFlow<SavedTripSearchUiState> = mutableState.asStateFlow()
  private val mutex = Mutex()
  private var pending = 0

  init {
    refresh()
  }

  fun refresh() = change { TripResult.Success(Unit) }

  fun save(name: String, criteria: TripSearchCriteria) = change {
    repository.saveSearch(name, criteria)
  }

  fun rename(id: String, name: String) = change { repository.renameSearch(id, name) }

  fun delete(id: String) = change { repository.deleteSearch(id) }

  /**
   * Runs [action], then reloads the list so it always reflects what is stored. Calls run one at a
   * time in order, so a later change is never overwritten by an earlier reload.
   */
  private fun change(action: suspend () -> TripResult<*>) {
    pending++
    mutableState.update { it.copy(isLoading = true, error = null) }
    viewModelScope.launch {
      mutex.withLock {
        var failure: TripError? = null
        var reloaded: TripResult<List<SavedTripSearch>>? = null
        try {
          failure = (action() as? TripResult.Error)?.error
          reloaded = repository.getSavedSearches()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          // An unexpected repository exception is reported like any failure; the list is kept.
          failure = failure ?: TripError.Unknown
        } finally {
          // Always runs, so loading never stays on after an exception or a cancellation.
          pending--
          mutableState.update { state ->
            when (val result = reloaded) {
              is TripResult.Success ->
                  state.copy(searches = result.data, isLoading = pending > 0, error = failure)
              is TripResult.Error ->
                  state.copy(isLoading = pending > 0, error = failure ?: result.error)
              null -> state.copy(isLoading = pending > 0, error = failure ?: TripError.Unknown)
            }
          }
        }
      }
    }
  }
}
