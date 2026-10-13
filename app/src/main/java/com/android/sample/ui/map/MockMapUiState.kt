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

data class MockMapUiState(
    val trips: List<MockMapTrip> = MockMapSamples.trips,
    val selectedTripId: String? = null,
) {
  val selectedTrip: MockMapTrip?
    get() = trips.firstOrNull { it.id == selectedTripId }

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
  const val MARKER_PREFIX = "mock_map_marker_"
  const val EMPTY = "mock_map_empty"
}
