package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.test.core.app.ApplicationProvider
import com.android.sample.MainDispatcherRule
import com.android.sample.data.repository.SavedTripSearchRepository
import com.android.sample.data.repository.SavedTripSearchRepositoryLocal
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripResult
import com.android.sample.model.DeparturePeriod
import com.android.sample.model.SavedTripSearch
import com.android.sample.model.StoreFilter
import com.android.sample.model.TripSearchCriteria
import com.android.sample.model.TripSort
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs against the real local repository; [Failing] only injects errors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedTripSearchViewModelTest {
  @get:Rule val mainRule = MainDispatcherRule()

  private val t0 = Instant.parse("2026-11-04T10:00:00Z")
  private var uid: String? = "alice"
  private val repository =
      Failing(
          SavedTripSearchRepositoryLocal(
              ApplicationProvider.getApplicationContext(),
              { uid },
              now = { t0 },
              ioDispatcher = mainRule.dispatcher,
          )
      )
  private val migros = TripSearchCriteria(store = StoreFilter.MIGROS)
  private val coop =
      TripSearchCriteria(
          StoreFilter.COOP,
          DeparturePeriod.Custom(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 9)),
          TripSort.DISTANCE,
      )

  /** Delegates to the real repository unless [error] is set. */
  private class Failing(private val base: SavedTripSearchRepository) :
      SavedTripSearchRepository by base {
    var error: TripError? = null

    override suspend fun getSavedSearches(): TripResult<List<SavedTripSearch>> =
        error?.let { TripResult.Error(it) } ?: base.getSavedSearches()

    override suspend fun saveSearch(
        name: String,
        criteria: TripSearchCriteria,
    ): TripResult<SavedTripSearch> =
        error?.let { TripResult.Error(it) } ?: base.saveSearch(name, criteria)
  }

  private fun SavedTripSearchViewModel.presets() =
      uiState.value.searches.map { it.name to it.criteria }

  private fun SavedTripSearchViewModel.idOf(name: String) =
      uiState.value.searches.single { it.name == name }.id

  @Test
  fun initialLoadListsStoredPresetsThenSaveValidatesName() = runTest {
    repository.saveSearch("Migros", migros)
    val viewModel = SavedTripSearchViewModel(repository)
    assertTrue(viewModel.uiState.value.isLoading)
    advanceUntilIdle()
    assertEquals(listOf("Migros" to migros), viewModel.presets())
    assertEquals(t0, viewModel.uiState.value.searches.single().createdAt)
    assertFalse(viewModel.uiState.value.isLoading)
    assertNull(viewModel.uiState.value.error)

    viewModel.save("  Coop week  ", coop)
    advanceUntilIdle()
    val before = viewModel.uiState.value.searches
    assertEquals(setOf("Migros" to migros, "Coop week" to coop), viewModel.presets().toSet())
    for (name in listOf("   ", "x".repeat(41))) {
      viewModel.save(name, coop)
      advanceUntilIdle()
      assertEquals(
          SavedTripSearchUiState(before, error = TripError.InvalidData),
          viewModel.uiState.value,
      )
    }
  }

  @Test
  fun renameAndDeleteChangeOnlyTheTargetPreset() = runTest {
    val viewModel = SavedTripSearchViewModel(repository)
    viewModel.save("Mine", migros)
    viewModel.save("Other", coop)
    advanceUntilIdle()
    viewModel.rename(viewModel.idOf("Mine"), " Migros only ")
    advanceUntilIdle()
    val renamed = setOf("Migros only" to migros, "Other" to coop)
    assertEquals(renamed, viewModel.presets().toSet())
    viewModel.rename("missing", "Name")
    advanceUntilIdle()
    assertEquals(renamed, viewModel.presets().toSet())
    assertEquals(TripError.NotFound, viewModel.uiState.value.error)

    viewModel.delete(viewModel.idOf("Migros only"))
    advanceUntilIdle()
    assertEquals(listOf("Other" to coop), viewModel.presets())
  }

  @Test
  fun repositoryErrorKeepsPreviousListAndNextSuccessClearsIt() = runTest {
    val viewModel = SavedTripSearchViewModel(repository)
    viewModel.save("Mine", migros)
    advanceUntilIdle()
    val list = viewModel.uiState.value.searches
    repository.error = TripError.NetworkError
    viewModel.save("Other", coop)
    advanceUntilIdle()
    assertEquals(
        SavedTripSearchUiState(list, error = TripError.NetworkError),
        viewModel.uiState.value,
    )
    viewModel.refresh()
    advanceUntilIdle()
    assertEquals(
        SavedTripSearchUiState(list, error = TripError.NetworkError),
        viewModel.uiState.value,
    )
    repository.error = null
    viewModel.refresh()
    assertEquals(SavedTripSearchUiState(list, isLoading = true), viewModel.uiState.value)
    advanceUntilIdle()
    assertEquals(SavedTripSearchUiState(list), viewModel.uiState.value)
  }

  @Test
  fun signedOutRepositoryIsPermissionDenied() = runTest {
    uid = null
    val viewModel = SavedTripSearchViewModel(repository)
    advanceUntilIdle()
    assertEquals(
        SavedTripSearchUiState(error = TripError.PermissionDenied),
        viewModel.uiState.value,
    )
    viewModel.save("Mine", migros)
    advanceUntilIdle()
    assertEquals(
        SavedTripSearchUiState(error = TripError.PermissionDenied),
        viewModel.uiState.value,
    )
    assertFalse(viewModel.uiState.value.isLoading)
  }
}
