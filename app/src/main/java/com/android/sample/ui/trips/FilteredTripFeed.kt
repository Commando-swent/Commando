package com.android.sample.ui.trips

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripFeed
import com.android.sample.model.classifyTrips
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Shared loading, cancellation and cached filtering for list/map; the owner supplies its scope. */
internal class FilteredTripFeed(
    private val scope: CoroutineScope,
    private val repository: TripRepository,
    private val selectedPoint: StateFlow<Location?>,
    private val publish: (TripFeed) -> Unit,
    private val loading: () -> Unit,
    private val error: (TripError) -> Unit,
) {
  private var loadedTrips: List<Trip>? = null
  private var loadJob: Job? = null

  init {
    scope.launch { selectedPoint.collect { loadedTrips?.let(::show) } }
  }

  /** Replaces any in-flight request; even a cancellation-swallowing repository cannot publish. */
  fun refresh() {
    loadJob?.cancel()
    loadedTrips = null
    loading()
    loadJob = scope.launch {
      val result =
          try {
            repository.getUpcomingTrips()
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            TripResult.Error(TripError.Unknown)
          }
      currentCoroutineContext().ensureActive()
      when (result) {
        is TripResult.Success -> {
          loadedTrips = result.data
          show(result.data)
        }
        is TripResult.Error -> error(result.error)
      }
    }
  }

  /** Drops cached data and cancels the current request when the owning session ends. */
  fun clear() {
    loadJob?.cancel()
    loadedTrips = null
  }

  private fun show(trips: List<Trip>) = publish(classifyTrips(trips, selectedPoint.value))
}
