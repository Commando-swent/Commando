package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.lifecycle.SavedStateHandle
import com.android.sample.model.Location
import com.android.sample.model.TripLocations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripLocationFilterViewModelTest {
  private val cm = TripLocations.handoffPoints.first { it.name == "CM building" }
  private val rolex = TripLocations.handoffPoints.first { it.name == "Rolex Learning Center" }
  private val gps = Location("My position", 46.5195012345, 6.5632098765)

  /** Simulates process death: only the values saved in [handle] survive into a new handle. */
  private fun recreated(handle: SavedStateHandle) =
      SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })

  @Test
  fun startsWithoutSelection() {
    assertNull(TripLocationFilterViewModel(SavedStateHandle()).selected.value)
  }

  @Test
  fun selectExposesTheFullPointReplacesItAndClearResets() {
    val viewModel = TripLocationFilterViewModel(SavedStateHandle())
    viewModel.select(cm)
    assertEquals(Location("CM building", 46.5205116, 6.5667474), viewModel.selected.value)
    viewModel.select(rolex)
    assertEquals(rolex, viewModel.selected.value)
    viewModel.clear()
    assertNull(viewModel.selected.value)
  }

  @Test
  fun selectionSurvivesProcessDeathIncludingNonCampusPoints() {
    val handle = SavedStateHandle()
    val viewModel = TripLocationFilterViewModel(handle)
    viewModel.select(cm)
    assertEquals(cm, TripLocationFilterViewModel(recreated(handle)).selected.value)
    viewModel.select(gps)
    assertEquals(gps, TripLocationFilterViewModel(recreated(handle)).selected.value)
    assertEquals(gps, TripLocationFilterViewModel(handle).selected.value)
  }

  @Test
  fun clearedSelectionIsNotRestored() {
    val handle = SavedStateHandle()
    val viewModel = TripLocationFilterViewModel(handle)
    viewModel.select(gps)
    viewModel.clear()
    assertNull(TripLocationFilterViewModel(recreated(handle)).selected.value)
    assertNull(TripLocationFilterViewModel(handle).selected.value)
  }

  @Test
  fun partiallySavedSelectionIsNotRestored() {
    val handle = SavedStateHandle()
    TripLocationFilterViewModel(handle).select(gps)
    // Drop the saved longitude, found by value so the test does not depend on private key names.
    val partial =
        SavedStateHandle(
            handle
                .keys()
                .filter { handle.get<Any>(it) != gps.longitude }
                .associateWith { handle.get<Any>(it) }
        )
    assertEquals(2, partial.keys().size)
    assertNull(TripLocationFilterViewModel(partial).selected.value)
  }
}
