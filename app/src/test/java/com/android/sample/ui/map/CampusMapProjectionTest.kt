package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import com.android.sample.model.Location
import org.junit.Assert.*
import org.junit.Test

class CampusMapProjectionTest {
  // Test bounds only, not the geographic calibration of the production campus artwork.
  private val projection =
      CampusMapProjection(south = 46.518, north = 46.524, west = 6.560, east = 6.572)

  @Test
  fun geographicCornersMapToImageCorners() {
    assertEquals(MapPosition(0.0, 0.0), projection.project(Location("NW", 46.524, 6.560)))
    assertEquals(MapPosition(1.0, 1.0), projection.project(Location("SE", 46.518, 6.572)))
  }

  @Test
  fun midpointIsCenteredRegardlessOfPlaceName() {
    val result = projection.project(Location("arbitrary handoff", 46.521, 6.566))!!
    assertEquals(0.5, result.x, 1e-10)
    assertEquals(0.5, result.y, 1e-10)
  }

  @Test
  fun offscreenPointsAreNotMovedOntoTheEdge() {
    for (point in
        listOf(
            Location("north", 46.525, 6.566),
            Location("west", 46.521, 6.559),
            Location("south", 46.517, 6.566),
            Location("east", 46.521, 6.573),
        )) {
      assertNull(projection.project(point))
    }
  }

  @Test
  fun invalidCoordinatesCannotProduceMarkerPositions() {
    assertNull(projection.project(Location("NaN", Double.NaN, 6.566)))
    assertNull(projection.project(Location("infinite", 46.521, Double.POSITIVE_INFINITY)))
  }

  @Test
  fun invalidOrInvertedBoundsAreRejected() {
    for (bounds in
        listOf(
            listOf(1.0, 0.0, 0.0, 1.0),
            listOf(0.0, 1.0, 1.0, 0.0),
            listOf(0.0, 0.0, 0.0, 1.0),
            listOf(-91.0, 1.0, 0.0, 1.0),
            listOf(0.0, 91.0, 0.0, 1.0),
            listOf(0.0, 1.0, -181.0, 1.0),
            listOf(0.0, 1.0, 0.0, 181.0),
            listOf(Double.NaN, 1.0, 0.0, 1.0),
        )) {
      assertThrows(IllegalArgumentException::class.java) {
        CampusMapProjection(bounds[0], bounds[1], bounds[2], bounds[3])
      }
    }
  }
}
