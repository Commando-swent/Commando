package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import com.android.sample.model.DeviceLocation
import com.android.sample.model.Location

internal const val LOCATION_CAMERA_ZOOM = 2f
private const val MAX_CAMERA_ZOOM = 3f
private const val MIN_CAMERA_ZOOM = 1f
private const val VIEWPORT_CENTER = 0.5

/** Camera transform shared by the static background, trip pins and device position. */
internal data class MapCamera
private constructor(
    val center: MapPosition,
    val zoom: Float,
) {
  constructor() : this(MapPosition(VIEWPORT_CENTER, VIEWPORT_CENTER), MIN_CAMERA_ZOOM)

  companion object {
    /** Keeps the drawing covering the viewport, including when recentering near campus edges. */
    fun centeredAt(center: MapPosition, zoom: Float): MapCamera {
      val boundedZoom = zoom.coerceIn(MIN_CAMERA_ZOOM, MAX_CAMERA_ZOOM)
      val margin = VIEWPORT_CENTER / boundedZoom
      return MapCamera(
          MapPosition(center.x.coerceIn(margin, 1 - margin), center.y.coerceIn(margin, 1 - margin)),
          boundedZoom,
      )
    }
  }

  /** Applies a viewport-relative drag, bounding the center using the resulting zoom. */
  fun moved(horizontalPanFraction: Double, verticalPanFraction: Double, zoomFactor: Float) =
      centeredAt(
          MapPosition(
              center.x - horizontalPanFraction / zoom,
              center.y - verticalPanFraction / zoom,
          ),
          zoom * zoomFactor,
      )

  /** Projects geographic fractions into the visible camera without scaling labels. */
  fun project(point: MapPosition) =
      MapPosition(
          (point.x - center.x) * zoom + VIEWPORT_CENTER,
          (point.y - center.y) * zoom + VIEWPORT_CENTER,
      )
}

/** Only in-campus fixes can recenter the bundled geographic drawing. */
internal fun DeviceLocation.campusPosition(): MapPosition? =
    CAMPUS_MAP_PROJECTION.project(Location("", latitude, longitude))

/** Whether a projected anchor is inside the current visible viewport. */
internal val MapPosition.inViewport: Boolean
  get() = x in 0.0..1.0 && y in 0.0..1.0
