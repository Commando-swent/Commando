package com.android.sample.ui.trips

// AI assistance: Claude Code.

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.android.sample.model.DeparturePeriod
import com.android.sample.model.Location
import com.android.sample.model.StoreFilter
import com.android.sample.model.TripSearchCriteria
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the delivery point and the search criteria a requester filters trips by, shared by the trip
 * list (#146) and map (#150), which read [selected] and [criteria] and apply
 * [com.android.sample.model.classifyTripSearch]. One instance per authenticated session, created on
 * the outer App entry next to the other session ViewModels; signing out or switching accounts
 * discards it, so a selection never leaks between accounts. The point survives process death and is
 * saved in full, so a building, map pin or GPS position is restored.
 */
class TripLocationFilterViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
  private val mutableSelected = MutableStateFlow(restore())
  val selected: StateFlow<Location?> = mutableSelected.asStateFlow()

  private val mutableCriteria = MutableStateFlow(TripSearchCriteria())
  val criteria: StateFlow<TripSearchCriteria> = mutableCriteria.asStateFlow()

  fun select(point: Location) {
    savedStateHandle[NAME_KEY] = point.name
    savedStateHandle[LATITUDE_KEY] = point.latitude
    savedStateHandle[LONGITUDE_KEY] = point.longitude
    mutableSelected.value = point
  }

  fun clear() {
    savedStateHandle.remove<String>(NAME_KEY)
    savedStateHandle.remove<Double>(LATITUDE_KEY)
    savedStateHandle.remove<Double>(LONGITUDE_KEY)
    mutableSelected.value = null
  }

  /** Clears the store and departure filters; the sort and the delivery point are kept. */
  fun clearCriteria() =
      setCriteria(
          criteria.value.copy(store = StoreFilter.ALL, period = DeparturePeriod.AnyTime),
      )

  /** Replaces all search criteria at once (e.g. a saved preset); the delivery point is kept. */
  fun setCriteria(criteria: TripSearchCriteria) {
    mutableCriteria.value = criteria
  }

  private fun restore(): Location? {
    val name = savedStateHandle.get<String>(NAME_KEY) ?: return null
    val latitude = savedStateHandle.get<Double>(LATITUDE_KEY) ?: return null
    val longitude = savedStateHandle.get<Double>(LONGITUDE_KEY) ?: return null
    return Location(name, latitude, longitude)
  }

  private companion object {
    const val NAME_KEY = "trip_filter_name"
    const val LATITUDE_KEY = "trip_filter_latitude"
    const val LONGITUDE_KEY = "trip_filter_longitude"
  }
}
