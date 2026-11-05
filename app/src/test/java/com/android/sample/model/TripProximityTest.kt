package com.android.sample.model

// AI assistance: OpenAI Codex.
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TripProximityTest {
  private val fix = DeviceLocation(46.521, 6.566, 150f, 1000)
  private val now = Instant.parse("2026-11-05T12:00:00Z")

  private fun trip(id: String, latitude: Double) =
      Trip(
          id,
          "owner",
          TripLocations.stores.first(),
          now.plusSeconds(3600),
          Location(id, latitude, fix.longitude),
          TripStatus.PUBLISHED,
          now,
          now,
      )

  @Test
  fun nearbyTripsKeepEligibleIdsAndSortByStraightLineDistance() {
    val near = trip("near", 46.5211)
    val farther = trip("farther", 46.522)
    val distant = trip("distant", 46.53)
    val invalid = trip("invalid", Double.NaN)
    assertEquals(listOf(near, farther), nearbyTrips(listOf(distant, farther, invalid, near), fix))
  }

  @Test
  fun distanceReusesSharedHaversineCalculation() {
    val trip = trip("other", 46.522)
    assertEquals(
        distanceMeters(Location("device", fix.latitude, fix.longitude), trip.handoffLocation),
        fix.distanceTo(trip)!!,
        .001,
    )
    assertEquals(111.2, fix.distanceTo(trip)!!, .1)
  }

  @Test
  fun invalidHandoffHasNoDeviceDistance() {
    assertNull(fix.distanceTo(trip("invalid", Double.NaN)))
  }

  @Test
  fun samePointHasZeroDistance() {
    assertEquals(0.0, fix.distanceTo(trip("same", fix.latitude))!!, .001)
  }

  @Test
  fun emptyEligibleFeedHasNoNearbyTrips() {
    assertEquals(emptyList<Trip>(), nearbyTrips(emptyList(), fix))
  }
}
