package com.android.sample.ui.trips

// AI assistance: Claude Code.

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.android.sample.model.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the delivery point a requester filters trips by, shared by the trip list (#146) and map
 * (#150), which read [selected] and apply `filterTripsServing`. One instance per authenticated
 * session, created on the outer App entry next to the other session ViewModels; signing out or
 * switching accounts discards it, so a selection never leaks between accounts. The full point is
 * saved, so a building, map pin or GPS position survives process death.
 */
class TripLocationFilterViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
  private val mutableSelected = MutableStateFlow(restore())
  val selected: StateFlow<Location?> = mutableSelected.asStateFlow()

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
