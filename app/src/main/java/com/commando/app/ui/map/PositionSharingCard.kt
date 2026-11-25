package com.commando.app.ui.map

// AI assistance: OpenAI Codex.
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.commando.app.R

/**
 * Existing Home card styling, with explicit opt-in and a persistent foreground sharing indicator.
 */
@Composable
fun PositionSharingCard(
    state: PositionSharingState,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    discovery: DiscoverySharingState = DiscoverySharingState.UNAVAILABLE,
    onDiscoveryToggle: (Boolean) -> Unit = {},
) {
  val title = stringResource(R.string.position_sharing_title)
  val discoveryTitle = stringResource(R.string.live_map_consent_title)
  val message =
      when (state) {
        PositionSharingState.Off -> R.string.position_sharing_off
        PositionSharingState.Starting -> R.string.position_sharing_starting
        PositionSharingState.Live -> R.string.position_sharing_live
        PositionSharingState.Stopping -> R.string.position_sharing_stopping
        is PositionSharingState.Failed ->
            when (state.reason) {
              SharingFailureReason.PERMISSION -> R.string.position_sharing_permission
              SharingFailureReason.LOCATION -> R.string.position_sharing_location
              SharingFailureReason.PUBLISH -> R.string.position_sharing_publish
              SharingFailureReason.STOP -> R.string.position_sharing_stop
              SharingFailureReason.NO_TRIP -> R.string.position_sharing_no_trip
            }
      }
  OutlinedCard(modifier.fillMaxWidth().testTag(PositionSharingTestTags.SHARING)) {
    Column {
      Row(
          Modifier.padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Column(Modifier.weight(1f)) {
          Text(
              title,
              style = MaterialTheme.typography.titleSmall,
          )
          Text(stringResource(message), style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            state == PositionSharingState.Starting ||
                state == PositionSharingState.Live ||
                state == PositionSharingState.Stopping ||
                state == PositionSharingState.Failed(SharingFailureReason.STOP),
            onToggle,
            enabled = state != PositionSharingState.Stopping,
            modifier =
                Modifier.testTag(PositionSharingTestTags.SHARING_TOGGLE).semantics {
                  contentDescription = title
                },
        )
      }
      Row(
          Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Column(Modifier.weight(1f)) {
          Text(discoveryTitle, style = MaterialTheme.typography.titleSmall)
          Text(
              stringResource(R.string.live_map_consent_hint),
              style = MaterialTheme.typography.bodySmall,
          )
        }
        Switch(
            discovery == DiscoverySharingState.DISCOVERABLE,
            onDiscoveryToggle,
            enabled =
                state == PositionSharingState.Live &&
                    discovery != DiscoverySharingState.UNAVAILABLE,
            modifier =
                Modifier.testTag(PositionSharingTestTags.DISCOVERY_TOGGLE).semantics {
                  contentDescription = discoveryTitle
                },
        )
      }
    }
  }
}

/** Shared semantics for opt-in and lifecycle tests. */
object PositionSharingTestTags {
  const val SHARING = "live_position_sharing"
  const val DISCOVERY_TOGGLE = "live_discovery_toggle"
  const val SHARING_TOGGLE = "live_position_toggle"
}
