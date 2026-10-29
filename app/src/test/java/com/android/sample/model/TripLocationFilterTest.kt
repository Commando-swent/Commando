package com.android.sample.model

// AI assistance: Claude Code; OpenAI Codex (classification tests).
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripLocationFilterTest {
  private val now = Instant.parse("2026-10-26T10:00:00Z")
  private val places = TripLocations.handoffPoints.associateBy { it.name }

  private fun place(name: String) = places.getValue(name)

  private fun trip(id: String, handoff: Location) =
      Trip(
          id = id,
          ownerId = "owner",
          store = Location("Migros EPFL", 46.5229222, 6.5656983),
          scheduledAt = now.plusSeconds(3600),
          handoffLocation = handoff,
          status = TripStatus.PUBLISHED,
          createdAt = now,
          updatedAt = now,
      )

  /** One trip per campus handoff point, deliberately not in alphabetical or distance order. */
  private val campusTrips =
      listOf(
              "SwissTech Convention Center",
              "SV building",
              "CE building",
              "BC building",
              "EPFL metro station",
              "Rolex Learning Center",
              "CO building",
              "INM building",
              "CM building",
          )
          .map { trip(it, place(it)) }

  /** A point [meters] due north of [from], using the same Earth radius as the production code. */
  private fun north(from: Location, meters: Double) =
      Location("offset", from.latitude + Math.toDegrees(meters / 6_371_008.8), from.longitude)

  @Test
  fun classificationDistinguishesEmptyNoMatchesAndOrderedMatches() {
    val point = place("CM building")
    assertEquals(TripFeed.Empty, classifyTrips(emptyList(), null))
    assertEquals(TripFeed.Empty, classifyTrips(emptyList(), point))
    assertEquals(
        TripFeed.NoMatches(Location("far", 0.0, 0.0)),
        classifyTrips(campusTrips, Location("far", 0.0, 0.0)),
    )
    assertEquals(TripFeed.Matches(campusTrips), classifyTrips(campusTrips, null))
    assertEquals(
        TripFeed.Matches(filterTripsServing(campusTrips, point)),
        classifyTrips(campusTrips, point),
    )
  }

  @Test
  fun noSelectedPointReturnsEveryTripInTheSameOrder() {
    val invalid = trip("invalid", Location("bad", Double.NaN, 6.56))
    val trips = campusTrips + invalid
    assertEquals(trips.map { it.id }, filterTripsServing(trips, null).map { it.id })
  }

  @Test
  fun keepsTripsWithinTheRadiusInInputOrderAndDropsTheOthers() {
    // From BC: INM 95 m, SV 202 m, CO 280 m, CM 429 m, RLC 491 m are inside;
    // metro 520 m, STCC 559 m and CE 689 m are outside.
    val result = filterTripsServing(campusTrips, place("BC building"))
    assertEquals(
        listOf(
            "SV building",
            "BC building",
            "Rolex Learning Center",
            "CO building",
            "INM building",
            "CM building",
        ),
        result.map { it.id },
    )
    assertEquals(500.0, HANDOFF_RADIUS_METERS, 0.0)
  }

  @Test
  fun radiusBoundaryIsInclusiveAndCustomRadiusIsHonoured() {
    val origin = place("CM building")
    val trips =
        listOf(
            trip("inside", north(origin, HANDOFF_RADIUS_METERS - 0.5)),
            trip("outside", north(origin, HANDOFF_RADIUS_METERS + 0.5)),
        )
    assertEquals(listOf("inside"), filterTripsServing(trips, origin).map { it.id })
    // CM to CO is about 152 m: excluded at 150 m, included at 155 m and at exactly the distance.
    val co = listOf(trip("co", place("CO building")))
    val exact = distanceMeters(origin, place("CO building"))
    assertEquals(listOf("co"), filterTripsServing(co, origin, exact).map { it.id })
    assertEquals(emptyList<String>(), filterTripsServing(co, origin, 150.0).map { it.id })
    assertEquals(listOf("co"), filterTripsServing(co, origin, 155.0).map { it.id })
  }

  @Test
  fun distanceIsZeroSymmetricAndMatchesReferenceValues() {
    val cm = place("CM building")
    val co = place("CO building")
    assertEquals(0.0, distanceMeters(cm, cm), 0.0)
    assertEquals(distanceMeters(cm, co), distanceMeters(co, cm), 1e-9)
    assertEquals(152.3, distanceMeters(cm, co), 1.0)
    assertEquals(
        609.1,
        distanceMeters(place("Rolex Learning Center"), place("SwissTech Convention Center")),
        1.0,
    )
    assertEquals(688.9, distanceMeters(place("BC building"), place("CE building")), 1.0)
    assertEquals(111_195.0, distanceMeters(Location("", 0.0, 0.0), Location("", 1.0, 0.0)), 1.0)
    // Across the antimeridian the short way round is 0.001 degree of longitude at the equator.
    assertEquals(
        111.2,
        distanceMeters(Location("", 0.0, 179.9995), Location("", 0.0, -179.9995)),
        1.0,
    )
  }

  @Test
  fun tripsWithInvalidHandoffCoordinatesAreDroppedButValidOnesKept() {
    val cm = place("CM building")
    val trips =
        listOf(
            trip("nan", Location("x", Double.NaN, cm.longitude)),
            trip("valid", cm),
            trip("infinite", Location("x", cm.latitude, Double.POSITIVE_INFINITY)),
            trip("lat91", Location("x", 91.0, cm.longitude)),
            trip("lon181", Location("x", cm.latitude, 181.0)),
        )
    assertEquals(listOf("valid"), filterTripsServing(trips, cm, Double.MAX_VALUE).map { it.id })
    assertEquals(trips, filterTripsServing(trips, null))
  }

  @Test
  fun coordinateValidityAcceptsExactLimitsOnly() {
    listOf(90.0 to 180.0, -90.0 to -180.0, 90.0 to -180.0, -90.0 to 180.0).forEach { (lat, lon) ->
      assertTrue("$lat,$lon", Location("", lat, lon).hasValidCoordinates())
    }
    listOf(
            Math.nextUp(90.0) to 0.0,
            Math.nextDown(-90.0) to 0.0,
            0.0 to Math.nextUp(180.0),
            0.0 to Math.nextDown(-180.0),
            Double.NaN to 0.0,
            0.0 to Double.NaN,
            Double.POSITIVE_INFINITY to 0.0,
            0.0 to Double.NEGATIVE_INFINITY,
        )
        .forEach { (lat, lon) ->
          assertFalse("$lat,$lon", Location("", lat, lon).hasValidCoordinates())
        }
  }

  @Test
  fun invalidSelectedPointMatchesNothing() {
    listOf(
            Location("x", Double.NaN, 6.56),
            Location("x", 46.52, Double.NEGATIVE_INFINITY),
            Location("x", -91.0, 6.56),
            Location("x", 46.52, -181.0),
        )
        .forEach {
          assertEquals(
              it.toString(),
              emptyList<Trip>(),
              filterTripsServing(campusTrips, it, Double.MAX_VALUE),
          )
        }
  }

  @Test
  fun arbitraryPointsOutsideTheCampusListAreSupported() {
    val myPosition = Location("My position", 46.51950, 6.56320)
    val unnamed = Location("", 46.2044, 6.1432) // Geneva, far from every campus point.
    val expected = listOf("SV building", "BC building", "CO building", "INM building")
    assertEquals(expected, filterTripsServing(campusTrips, myPosition, 200.0).map { it.id })
    assertEquals(emptyList<Trip>(), filterTripsServing(campusTrips, unnamed))
    val geneva = listOf(trip("geneva", Location("Plainpalais", 46.1984, 6.1423)))
    assertEquals(listOf("geneva"), filterTripsServing(geneva, unnamed, 1_000.0).map { it.id })
  }
}
