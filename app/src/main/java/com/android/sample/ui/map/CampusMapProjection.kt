package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import com.android.sample.model.Location
import com.android.sample.model.hasValidCoordinates

/** Fractional map position: (0, 0) is the northwest corner, (1, 1) the southeast corner. */
data class MapPosition(val x: Double, val y: Double)

/**
 * North-up equirectangular projection for a campus viewport, without antimeridian crossing.
 * Longitude and latitude map linearly to the viewport's width and height. The renderer must use a
 * background with these same geographic bounds and projection; this does not calibrate artwork.
 * Bounds are supplied explicitly rather than inferred from trip fixtures.
 */
class CampusMapProjection(
    private val south: Double,
    private val north: Double,
    private val west: Double,
    private val east: Double,
) {
  init {
    require(south.isFinite() && north.isFinite() && south >= -90 && north <= 90 && south < north) {
      "Invalid latitude bounds: $south..$north"
    }
    require(west.isFinite() && east.isFinite() && west >= -180 && east <= 180 && west < east) {
      "Invalid longitude bounds: $west..$east"
    }
  }

  /** Returns null for invalid/offscreen coordinates, without clamping them to a false location. */
  fun project(location: Location): MapPosition? {
    if (
        !location.hasValidCoordinates() ||
            location.latitude !in south..north ||
            location.longitude !in west..east
    )
        return null
    return MapPosition(
        x = (location.longitude - west) / (east - west),
        y = (north - location.latitude) / (north - south),
    )
  }
}
