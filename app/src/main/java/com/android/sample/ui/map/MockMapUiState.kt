package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
/** UI-only fixture: positions are fractions of the illustration, never geographic coordinates. */
data class MockMapTrip(
    val id: String,
    val store: String,
    val time: String,
    val handoff: String,
    val x: Float,
    val y: Float,
    val handoffPointId: String = handoff,
)

enum class MockMapStatus {
  Content,
  Loading,
  Error,
}

data class MockMapUiState(
    val trips: List<MockMapTrip> = MockMapSamples.trips,
    val selectedTripId: String? = null,
    val status: MockMapStatus = MockMapStatus.Content,
    val locationOff: Boolean = false,
) {
  val selectedTrip: MockMapTrip?
    get() =
        if (status == MockMapStatus.Content) trips.firstOrNull { it.id == selectedTripId } else null

  val markerGroups: List<List<MockMapTrip>>
    get() = trips.groupBy { it.handoffPointId }.values.toList()

  val selectedGroup: List<MockMapTrip>
    get() =
        selectedTrip
            ?.let { selected -> trips.filter { it.handoffPointId == selected.handoffPointId } }
            .orEmpty()
}

object MockMapSamples {
  val trips =
      listOf(
          MockMapTrip(
              "sample-migros",
              "Migros EPFL",
              "12:30",
              "CM building",
              232f / 412,
              233f / 749,
          ),
          MockMapTrip(
              "sample-denner",
              "Denner EPFL",
              "17:30",
              "Rolex Learning Center",
              150f / 412,
              385f / 749,
          ),
          MockMapTrip(
              "sample-shared",
              "Migros EPFL",
              "18:15",
              "Rolex Learning Center",
              150f / 412,
              385f / 749,
          ),
          MockMapTrip("sample-coop", "Coop", "18:00", "Esplanade", 92f / 412, 277f / 749),
          MockMapTrip(
              "sample-market",
              "Campus market",
              "16:00",
              "Metro EPFL",
              306f / 412,
              151f / 749,
          ),
      )
}

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
