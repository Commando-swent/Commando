package com.android.sample.data.repository

// AI assistance: Claude Code.
import com.android.sample.model.SavedTripSearch
import com.android.sample.model.TripSearchCriteria

/**
 * The signed-in user's saved trip-search presets. Every call acts on the current user only; without
 * one it returns [TripError.PermissionDenied]. Applying a preset is a UI concern (its criteria
 * replace the active ones), so it has no repository call.
 */
interface SavedTripSearchRepository {
  /** The user's presets, oldest first, then by ID. */
  suspend fun getSavedSearches(): TripResult<List<SavedTripSearch>>

  /** Saves [criteria] under the trimmed [name]; a blank or too long name is InvalidData. */
  suspend fun saveSearch(name: String, criteria: TripSearchCriteria): TripResult<SavedTripSearch>

  /** Renames preset [id]; an unknown ID is NotFound, an invalid name InvalidData. */
  suspend fun renameSearch(id: String, name: String): TripResult<Unit>

  /** Deletes preset [id]; deleting an already missing preset succeeds. */
  suspend fun deleteSearch(id: String): TripResult<Unit>
}
