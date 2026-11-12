package com.android.sample.ui.trips

// AI assistance: Claude Code.
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.android.sample.R
import com.android.sample.data.repository.TripError
import com.android.sample.model.SavedTripSearch
import com.android.sample.model.StoreFilter
import com.android.sample.model.TripSearchCriteria
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class SavedTripSearchesTest {
  @get:Rule val compose = createComposeRule()

  private fun string(id: Int): String =
      ApplicationProvider.getApplicationContext<Context>().getString(id)

  private val nameLabel
    get() = string(R.string.trips_presets_name_label)

  private val t0 = Instant.parse("2026-11-04T10:00:00Z")
  private val mine = SavedTripSearch("search-1", "Mine", TripSearchCriteria(StoreFilter.MIGROS), t0)
  private val other = SavedTripSearch("search-2", "Other", TripSearchCriteria(StoreFilter.COOP), t0)
  private var state by mutableStateOf(SavedTripSearchUiState())
  private val saved = mutableListOf<String>()
  private val applied = mutableListOf<SavedTripSearch>()
  private val renamed = mutableListOf<Pair<String, String>>()
  private val deleted = mutableListOf<String>()

  private fun show(initial: SavedTripSearchUiState) {
    state = initial
    compose.setContent {
      MaterialTheme {
        SavedTripSearches(
            state = state,
            onSave = { saved += it },
            onApply = { applied += it },
            onRename = { id, name -> renamed += id to name },
            onDelete = { deleted += it },
        )
      }
    }
  }

  private fun tag(tag: String) = compose.onNodeWithTag(tag)

  private fun typeName(name: String) =
      tag(SavedTripSearchTestTags.NAME_FIELD).performTextReplacement(name)

  private fun assertNoEvents() {
    assertEquals(emptyList<String>(), saved)
    assertEquals(emptyList<SavedTripSearch>(), applied)
    assertEquals(emptyList<Pair<String, String>>(), renamed)
    assertEquals(emptyList<String>(), deleted)
  }

  @Test
  fun emptyStateShowsLabelEmptyTextAndNoError() {
    show(SavedTripSearchUiState())
    compose.onNodeWithText(string(R.string.trips_presets_label)).assertExists()
    tag(SavedTripSearchTestTags.EMPTY).assertTextEquals(string(R.string.trips_presets_empty))
    tag(SavedTripSearchTestTags.ERROR).assertDoesNotExist()
    tag(SavedTripSearchTestTags.SAVE).assertTextEquals(string(R.string.trips_presets_save))
  }

  @Test
  fun loadingWithoutPresetsHidesTheEmptyText() {
    show(SavedTripSearchUiState(isLoading = true))
    tag(SavedTripSearchTestTags.EMPTY).assertDoesNotExist()
  }

  @Test
  fun rowsListEachPresetAndTappingOneAppliesThatExactPreset() {
    show(SavedTripSearchUiState(listOf(mine, other)))
    tag(SavedTripSearchTestTags.EMPTY).assertDoesNotExist()
    tag(SavedTripSearchTestTags.apply(mine.id)).assertTextEquals("Mine")
    tag(SavedTripSearchTestTags.apply(other.id)).assertTextEquals("Other")
    tag(SavedTripSearchTestTags.rename(mine.id))
        .assertTextEquals(string(R.string.trips_presets_rename))
    tag(SavedTripSearchTestTags.delete(other.id))
        .assertTextEquals(string(R.string.trips_presets_delete))
    tag(SavedTripSearchTestTags.apply(other.id)).performClick()
    assertEquals(listOf(other), applied)
    assertEquals(emptyList<String>(), deleted)
  }

  @Test
  fun saveDialogEnablesConfirmOnlyForValidNamesAndEmitsTrimmedName() {
    show(SavedTripSearchUiState())
    tag(SavedTripSearchTestTags.SAVE).performClick()
    compose.onNodeWithText(string(R.string.trips_presets_save_title)).assertExists()
    tag(SavedTripSearchTestTags.CONFIRM).assertIsNotEnabled()
    typeName("   ")
    tag(SavedTripSearchTestTags.CONFIRM).assertIsNotEnabled()
    typeName("x".repeat(41))
    tag(SavedTripSearchTestTags.CONFIRM).assertIsNotEnabled()
    typeName("x".repeat(40))
    tag(SavedTripSearchTestTags.CONFIRM).assertIsEnabled()
    typeName("  Migros only  ")
    tag(SavedTripSearchTestTags.CONFIRM).assertIsEnabled().performClick()
    assertEquals(listOf("Migros only"), saved)
    tag(SavedTripSearchTestTags.NAME_FIELD).assertDoesNotExist()
  }

  @Test
  fun nameInputIsCappedAtFortyOneCharacters() {
    show(SavedTripSearchUiState())
    tag(SavedTripSearchTestTags.SAVE).performClick()
    typeName("y".repeat(60))
    tag(SavedTripSearchTestTags.NAME_FIELD).assertTextEquals("y".repeat(41), nameLabel)
    tag(SavedTripSearchTestTags.CONFIRM).assertIsNotEnabled()
  }

  @Test
  fun cancellingTheSaveDialogEmitsNothing() {
    show(SavedTripSearchUiState())
    tag(SavedTripSearchTestTags.SAVE).performClick()
    typeName("Mine")
    compose.onNodeWithText(string(R.string.create_trip_dialog_cancel)).performClick()
    tag(SavedTripSearchTestTags.NAME_FIELD).assertDoesNotExist()
    assertNoEvents()
    // Reopening starts from an empty name again.
    tag(SavedTripSearchTestTags.SAVE).performClick()
    tag(SavedTripSearchTestTags.NAME_FIELD).assertTextEquals("", nameLabel)
  }

  @Test
  fun renameDialogIsPrefilledAndEmitsIdAndNewName() {
    show(SavedTripSearchUiState(listOf(mine, other)))
    tag(SavedTripSearchTestTags.rename(other.id)).performClick()
    compose.onNodeWithText(string(R.string.trips_presets_rename_title)).assertExists()
    tag(SavedTripSearchTestTags.NAME_FIELD).assertTextEquals("Other", nameLabel)
    tag(SavedTripSearchTestTags.CONFIRM).assertIsEnabled()
    typeName(" Coop only ")
    tag(SavedTripSearchTestTags.CONFIRM).performClick()
    assertEquals(listOf(other.id to "Coop only"), renamed)
    tag(SavedTripSearchTestTags.NAME_FIELD).assertDoesNotExist()
    assertEquals(emptyList<String>(), saved)
  }

  @Test
  fun deleteEmitsThatId() {
    show(SavedTripSearchUiState(listOf(mine, other)))
    tag(SavedTripSearchTestTags.delete(mine.id)).performClick()
    assertEquals(listOf(mine.id), deleted)
    assertEquals(emptyList<SavedTripSearch>(), applied)
  }

  @Test
  fun errorTextIsShownWithTheKeptList() {
    show(SavedTripSearchUiState(listOf(mine), error = TripError.NetworkError))
    tag(SavedTripSearchTestTags.ERROR).assertTextEquals(string(R.string.trips_presets_error))
    tag(SavedTripSearchTestTags.apply(mine.id)).assertTextEquals("Mine")
    compose.runOnIdle { state = state.copy(error = null) }
    tag(SavedTripSearchTestTags.ERROR).assertDoesNotExist()
  }
}
