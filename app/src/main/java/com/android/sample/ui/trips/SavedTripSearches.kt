package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.model.SAVED_SEARCH_NAME_MAX_LENGTH
import com.android.sample.model.SavedTripSearch
import com.android.sample.model.normalizedSearchName

object SavedTripSearchTestTags {
  const val SAVE = "savedSearchSave"
  const val EMPTY = "savedSearchEmpty"
  const val ERROR = "savedSearchError"
  const val NAME_FIELD = "savedSearchNameField"
  const val CONFIRM = "savedSearchConfirm"

  fun apply(id: String) = "savedSearchApply_$id"

  fun rename(id: String) = "savedSearchRename_$id"

  fun delete(id: String) = "savedSearchDelete_$id"
}

/**
 * Lists the user's presets: tapping one applies it, and each can be renamed or deleted. "Save this
 * search" stores the current criteria under a name. The delivery point is never part of a preset.
 */
@Composable
fun SavedTripSearches(
    state: SavedTripSearchUiState,
    onSave: (String) -> Unit,
    onApply: (SavedTripSearch) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  var saving by remember { mutableStateOf(false) }
  var renamingId by remember { mutableStateOf<String?>(null) }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
        stringResource(R.string.trips_presets_label),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
    if (state.searches.isEmpty() && !state.isLoading) {
      Text(
          stringResource(R.string.trips_presets_empty),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.testTag(SavedTripSearchTestTags.EMPTY),
      )
    }
    state.searches.forEach { search ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { onApply(search) },
            modifier = Modifier.weight(1f).testTag(SavedTripSearchTestTags.apply(search.id)),
        ) {
          Text(search.name, modifier = Modifier.fillMaxWidth())
        }
        TextButton(
            onClick = { renamingId = search.id },
            modifier = Modifier.testTag(SavedTripSearchTestTags.rename(search.id)),
        ) {
          Text(stringResource(R.string.trips_presets_rename))
        }
        TextButton(
            onClick = { onDelete(search.id) },
            modifier = Modifier.testTag(SavedTripSearchTestTags.delete(search.id)),
        ) {
          Text(stringResource(R.string.trips_presets_delete))
        }
      }
    }
    if (state.error != null) {
      Text(
          stringResource(R.string.trips_presets_error),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.testTag(SavedTripSearchTestTags.ERROR),
      )
    }
    TextButton(
        onClick = { saving = true },
        modifier = Modifier.testTag(SavedTripSearchTestTags.SAVE),
    ) {
      Text(stringResource(R.string.trips_presets_save))
    }
  }

  if (saving) {
    PresetNameDialog(R.string.trips_presets_save_title, "", { saving = false }) {
      saving = false
      onSave(it)
    }
  }
  state.searches
      .find { it.id == renamingId }
      ?.let { search ->
        PresetNameDialog(R.string.trips_presets_rename_title, search.name, { renamingId = null }) {
          renamingId = null
          onRename(search.id, it)
        }
      }
}

/**
 * Asks for a preset name; confirm stays disabled until the name is 1–40 characters once trimmed.
 */
@Composable
private fun PresetNameDialog(
    title: Int,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
  var name by remember { mutableStateOf(initial) }
  val valid = normalizedSearchName(name)
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(title)) },
      text = {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(SAVED_SEARCH_NAME_MAX_LENGTH + 1) },
            label = { Text(stringResource(R.string.trips_presets_name_label)) },
            singleLine = true,
            isError = name.isNotEmpty() && valid == null,
            modifier = Modifier.testTag(SavedTripSearchTestTags.NAME_FIELD),
        )
      },
      confirmButton = {
        TextButton(
            onClick = { valid?.let(onConfirm) },
            enabled = valid != null,
            modifier = Modifier.testTag(SavedTripSearchTestTags.CONFIRM),
        ) {
          Text(stringResource(R.string.create_trip_dialog_ok))
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.create_trip_dialog_cancel)) }
      },
  )
}
