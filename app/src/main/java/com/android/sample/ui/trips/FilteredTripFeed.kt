package com.android.sample.ui.trips

// AI assistance: OpenAI Codex; Claude Code (search criteria).
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripFeed
import com.android.sample.model.TripSearchCriteria
import com.android.sample.model.classifyTripSearch
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Shared loading, cancellation and cached filtering for list/map; the owner supplies its scope. A
 * change of point or criteria re-filters the cached trips without a network call. Day-based periods
 * and expiry are evaluated against [clock] each time results are shown (load, reload, filter
 * change), so a screen left open past midnight updates on its next refresh or resume.
 */
internal class FilteredTripFeed(
    private val scope: CoroutineScope,
    private val repository: TripRepository,
    private val selectedPoint: StateFlow<Location?>,
    private val criteria: StateFlow<TripSearchCriteria>,
    private val clock: Clock,
    private val publish: (TripFeed) -> Unit,
    private val loading: () -> Unit,
    private val error: (TripError) -> Unit,
) {
  private var loadedTrips: List<Trip>? = null
  private var loadJob: Job? = null

  init {
    scope.launch { combine(selectedPoint, criteria, ::Pair).collect { loadedTrips?.let(::show) } }
  }

  /** Replaces any in-flight request; even a cancellation-swallowing repository cannot publish. */
  fun refresh() {
    loadJob?.cancel()
    loadedTrips = null
    loading()
    loadJob = scope.launch {
      repository
          .observeUpcomingTrips()
          .catch { cause ->
            if (cause is CancellationException) throw cause
            emit(TripResult.Error(TripError.Unknown))
          }
          .collect { result ->
            currentCoroutineContext().ensureActive()
            when (result) {
              is TripResult.Success -> {
                loadedTrips = result.data
                show(result.data)
              }
              is TripResult.Error -> {
                loadedTrips = null
                error(result.error)
              }
            }
          }
    }
  }

  /** Drops cached data and cancels the current request when the owning session ends. */
  fun clear() {
    loadJob?.cancel()
    loadedTrips = null
  }

  private fun show(trips: List<Trip>) =
      publish(
          classifyTripSearch(
              trips,
              selectedPoint.value,
              criteria.value,
              clock.instant(),
              clock.zone,
          )
      )
}
