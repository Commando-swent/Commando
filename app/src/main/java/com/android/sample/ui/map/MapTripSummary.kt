package com.android.sample.ui.map

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

object MockMapTestTags {
  const val SKELETON_PREFIX = "mock_map_skeleton_"
  const val MARKER_PREFIX = "mock_map_marker_"
  const val SUMMARY = "mock_map_summary"
  const val DETAILS = "mock_map_details"
  const val LOADING = "mock_map_loading"
  const val ERROR = "mock_map_error"
  const val RETRY = "mock_map_retry"
  const val ALLOW = "mock_map_allow"
  const val LOCATION_OFF = "mock_map_location_off"
  const val LOCATION = "mock_map_sample_location"
  const val PAGE_PREFIX = "mock_map_page_"
  const val EMPTY = "mock_map_empty"
}
