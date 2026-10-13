package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import org.junit.Assert.*
import org.junit.Test

class MockMapViewModelTest {
  @Test
  fun sharedPointsGroupTripsAndFollowSelectionWithoutStaleData() {
    val vm = MockMapViewModel()
    assertTrue(vm.uiState.value.selectedGroup.isEmpty())
    assertEquals(4, vm.uiState.value.markerGroups.size)
    vm.selectTrip("sample-shared")
    assertEquals(
        listOf("sample-denner", "sample-shared"),
        vm.uiState.value.selectedGroup.map { it.id },
    )
    vm.selectTrip("sample-coop")
    assertEquals(listOf("sample-coop"), vm.uiState.value.selectedGroup.map { it.id })
    val stale = vm.uiState.value.copy(trips = emptyList())
    assertNull(stale.selectedTrip)
    assertTrue(stale.selectedGroup.isEmpty())
  }

  @Test
  fun selectionIgnoresUnknownIdsAndCanBeCleared() {
    val vm = MockMapViewModel()
    assertNull(vm.uiState.value.selectedTrip)
    vm.selectTrip("sample-migros")
    assertEquals("Migros EPFL", vm.uiState.value.selectedTrip?.store)
    vm.selectTrip("unknown")
    assertEquals("sample-migros", vm.uiState.value.selectedTripId)
    vm.clearSelection()
    assertNull(vm.uiState.value.selectedTrip)
  }

  @Test
  fun emptySamplesCannotBeSelectedAndCallerMutationCannotChangeState() {
    val samples = mutableListOf<MockMapTrip>()
    val vm = MockMapViewModel(samples)
    samples.add(MockMapSamples.trips.first())
    vm.selectTrip(samples.first().id)
    assertTrue(vm.uiState.value.trips.isEmpty())
    assertNull(vm.uiState.value.selectedTrip)
  }
}
