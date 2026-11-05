package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import com.android.sample.model.DeviceLocation
import com.android.sample.model.Location

internal const val LOCATION_CAMERA_ZOOM = 2f
private const val MAX_CAMERA_ZOOM = 3f
private const val MIN_CAMERA_ZOOM = 1f
private const val VIEWPORT_CENTER = 0.5

/** Camera transform shared by the static background, trip pins and device position. */
internal data class MapCamera(
    val center: MapPosition = MapPosition(VIEWPORT_CENTER, VIEWPORT_CENTER),
    val zoom: Float = MIN_CAMERA_ZOOM,
) {
  /** Applies a viewport-relative drag and clamps zoom to the supported campus scale. */
  fun moved(horizontalPanFraction: Double, verticalPanFraction: Double, zoomFactor: Float) =
      MapCamera(
          MapPosition(
              (center.x - horizontalPanFraction / zoom).coerceIn(0.0, 1.0),
              (center.y - verticalPanFraction / zoom).coerceIn(0.0, 1.0),
          ),
          (zoom * zoomFactor).coerceIn(MIN_CAMERA_ZOOM, MAX_CAMERA_ZOOM),
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
