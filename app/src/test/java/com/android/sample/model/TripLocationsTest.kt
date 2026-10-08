package com.android.sample.model

// AI assistance: Claude Code.
import org.junit.Assert.*
import org.junit.Test

class TripLocationsTest {

  @Test
  fun everyPlaceHasANameAndValidCoordinates() {
    val places = TripLocations.stores + TripLocations.handoffPoints
    assertTrue(TripLocations.stores.isNotEmpty() && TripLocations.handoffPoints.isNotEmpty())
    assertEquals(places.size, places.map { it.name }.toSet().size)
    places.forEach {
      assertTrue(it.name, it.name.isNotBlank())
      assertTrue(it.name, it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0)
    }
  }
}
