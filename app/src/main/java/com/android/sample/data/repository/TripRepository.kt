package com.android.sample.data.repository

import com.android.sample.model.NewTrip
import com.android.sample.model.Trip

interface TripRepository {

  suspend fun publishTrip(newTrip: NewTrip): TripResult<Trip>

  suspend fun getTripById(tripId: String): TripResult<Trip>

  suspend fun getUpcomingTrips(): TripResult<List<Trip>>

  suspend fun getMyTrips(): TripResult<List<Trip>>
}
