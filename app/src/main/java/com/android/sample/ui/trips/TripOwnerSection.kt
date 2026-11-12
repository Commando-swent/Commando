package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.data.repository.TripError
import com.android.sample.model.TripStatus
import com.android.sample.model.TripTransition

object TripOwnerTestTags {
  const val TRANSITION = "trip_owner_transition"
  const val TRANSITION_ERROR = "trip_owner_transition_error"
  const val CONFIRM = "trip_owner_confirm"
  const val DISMISS = "trip_owner_dismiss"
}

private val SECTION_SPACING = 8.dp

/**
 * Owner-only actions of Trip Details: [onRequestTransition] asks to close or complete the trip, and
 * [onConfirmTransition] and [onDismissTransition] answer the confirmation.
 */
class TripOwnerControls(
    val onRequestTransition: (TripTransition) -> Unit,
    val onConfirmTransition: () -> Unit,
    val onDismissTransition: () -> Unit,
)

/** The only transition the owner can start from [status], or null once the trip has ended. */
internal fun nextTransition(status: TripStatus): TripTransition? =
    TripTransition.entries.firstOrNull { it.source == status }

/** The close or complete action and its confirmation for the owner of [state]'s trip. */
@Composable
internal fun TripOwnerSection(state: TripDetailsUiState.Content, controls: TripOwnerControls) {
  val transition = nextTransition(state.trip.status) ?: return
  Column(verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)) {
    TripDetailsButton(
        text = stringResource(transition.texts().action),
        onClick = { controls.onRequestTransition(transition) },
        enabled = !state.transitionInProgress,
        modifier = Modifier.testTag(TripOwnerTestTags.TRANSITION),
    )
    state.transitionError?.let { error ->
      Text(
          stringResource(error.transitionMessage()),
          modifier =
              Modifier.fillMaxWidth().testTag(TripOwnerTestTags.TRANSITION_ERROR).semantics {
                liveRegion = LiveRegionMode.Polite
              },
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
  state.pendingTransition?.let { pending ->
    AlertDialog(
        onDismissRequest = controls.onDismissTransition,
        title = { Text(stringResource(pending.texts().title)) },
        text = { Text(stringResource(pending.texts().message)) },
        confirmButton = {
          TextButton(
              onClick = controls.onConfirmTransition,
              modifier = Modifier.testTag(TripOwnerTestTags.CONFIRM),
          ) {
            Text(stringResource(R.string.trip_details_confirm))
          }
        },
        dismissButton = {
          TextButton(
              onClick = controls.onDismissTransition,
              modifier = Modifier.testTag(TripOwnerTestTags.DISMISS),
          ) {
            Text(stringResource(R.string.trip_details_cancel))
          }
        },
    )
  }
}

/** Button label, confirmation title and confirmation message of a transition. */
private class TransitionTexts(
    @StringRes val action: Int,
    @StringRes val title: Int,
    @StringRes val message: Int,
)

private fun TripTransition.texts(): TransitionTexts =
    when (this) {
      TripTransition.CLOSE ->
          TransitionTexts(
              R.string.trip_details_close,
              R.string.trip_details_close_title,
              R.string.trip_details_close_message,
          )
      TripTransition.COMPLETE ->
          TransitionTexts(
              R.string.trip_details_complete,
              R.string.trip_details_complete_title,
              R.string.trip_details_complete_message,
          )
    }

/** Message for a failed close or complete; every other error asks the owner to try again. */
@StringRes
private fun TripError.transitionMessage(): Int =
    when (this) {
      TripError.OrdersStillOpen -> R.string.trip_details_error_orders_open
      TripError.InvalidTransition,
      TripError.NotFound -> R.string.trip_details_error_status_changed
      TripError.NetworkError -> R.string.trip_details_error_network
      TripError.PermissionDenied,
      TripError.InvalidData,
      TripError.Unknown -> R.string.trip_details_error_update
    }
