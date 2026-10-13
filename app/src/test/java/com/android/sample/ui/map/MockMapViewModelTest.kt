package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import org.junit.Assert.*
import org.junit.Test

class MockMapViewModelTest {
  @Test
  fun retryAndLocationSimulationPreserveInjectedDataAndIgnoreUnavailableSelection() {
    val sample = MockMapSamples.trips.first()
    val vm = MockMapViewModel(listOf(sample), MockMapStatus.Error, locationOff = true)
    vm.selectTrip(sample.id)
    assertNull(vm.uiState.value.selectedTrip)
    vm.retry()
    assertEquals(MockMapStatus.Content, vm.uiState.value.status)
    assertEquals(listOf(sample), vm.uiState.value.trips)
    assertTrue(vm.uiState.value.locationOff)
    vm.enableSampleLocation()
    assertFalse(vm.uiState.value.locationOff)
    vm.selectTrip(sample.id)
    assertEquals(sample, vm.uiState.value.selectedTrip)
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
