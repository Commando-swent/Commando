package com.android.sample.ui.map

// AI assistance: OpenAI Codex (debug-only prototype extraction).
typealias MockMapTrip = MapTripSummary

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
  private val fixtures =
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

  val trips = fixtures.map { it.copy(isSample = true) }
}
