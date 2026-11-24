package com.commando.app.ui.map

// AI assistance: OpenAI Codex.
/** Display text and fractional positions shared by real maps and illustration previews. */
data class MapTripSummary(
    val id: String,
    val store: String,
    val time: String,
    val handoff: String,
    val x: Float,
    val y: Float,
    val handoffPointId: String = handoff,
    val isSample: Boolean = false,
    val distanceLabel: String? = null,
)

/** Stable tags shared by the production map and illustration previews. */
object MapTestTags {
  const val SKELETON_PREFIX = "map_skeleton_"
  const val MARKER_PREFIX = "map_marker_"
  const val SUMMARY = "map_summary"
  const val DETAILS = "map_details"
  const val LOADING = "map_loading"
  const val ERROR = "map_error"
  const val RETRY = "map_retry"
  const val ALLOW = "map_allow"
  const val LOCATION_OFF = "map_location_off"
  const val LOCATION = "map_sample_location"
  const val PAGE_PREFIX = "map_page_"
  const val EMPTY = "map_empty"
}
