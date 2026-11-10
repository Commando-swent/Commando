package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.model.Location

/**
 * Existing Home card styling, with explicit opt-in and a persistent foreground sharing indicator.
 */
@Composable
fun PositionSharingCard(
    state: PositionSharingState,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
  val title = stringResource(R.string.position_sharing_title)
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
  OutlinedCard(modifier.fillMaxWidth().testTag(LiveMapTestTags.SHARING)) {
    Row(
        Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(Modifier.weight(1f)) {
        Text(
            stringResource(R.string.position_sharing_title),
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
              Modifier.testTag(LiveMapTestTags.SHARING_TOGGLE).semantics {
                contentDescription = title
              },
      )
    }
  }
}

/** Every tracked commando stays reachable even with missing or off-campus coordinates. */
@Composable
internal fun LiveMapPanel(
    state: LiveMapState,
    selectedOwner: String?,
    onSelectOwner: (String?) -> Unit,
    onTrip: (String) -> Unit,
    onOrder: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  when (state) {
    LiveMapState.Loading ->
        Text(stringResource(R.string.live_map_loading), modifier.testTag(LiveMapTestTags.LOADING))
    LiveMapState.Unavailable ->
        TextButton(onRetry, modifier.testTag(LiveMapTestTags.RETRY)) {
          Text(stringResource(R.string.live_map_unavailable))
        }
    is LiveMapState.Content -> {
      val tracking = state.mode == LiveMapMode.TRACKING
      val count = state.commandos.size
      OutlinedCard(
          modifier
              .fillMaxWidth()
              .testTag(if (tracking) LiveMapTestTags.TRACKING else LiveMapTestTags.DISCOVERY)
      ) {
        Column(
            Modifier.padding(horizontal = 12.dp)
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
        ) {
          TextButton({
            expanded = !expanded
            if (!expanded) onSelectOwner(null)
          }) {
            Text(
                if (!tracking && count == 0) stringResource(R.string.live_map_none)
                else
                    pluralStringResource(
                        if (tracking) R.plurals.live_tracking_count
                        else R.plurals.live_discovery_count,
                        count,
                        count,
                    )
            )
          }
          if (tracking || expanded || selectedOwner != null)
              state.commandos.forEach { person ->
                TextButton(
                    {
                      onSelectOwner(if (selectedOwner == person.ownerId) null else person.ownerId)
                    },
                    Modifier.fillMaxWidth().testTag(LiveMapTestTags.PERSON_PREFIX + person.ownerId),
                ) {
                  Column(Modifier.fillMaxWidth()) {
                    Text(person.trips.map { it.store.name }.distinct().joinToString(" · "))
                    val position = person.position
                    Text(
                        stringResource(
                            when {
                              position == null -> R.string.live_map_position_unavailable
                              CAMPUS_MAP_PROJECTION.project(
                                  Location("", position.latitude, position.longitude)
                              ) == null -> R.string.live_map_outside
                              else -> R.string.live_map_position_live
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                  }
                }
                if (selectedOwner == person.ownerId) {
                  if (tracking)
                      person.orders.forEachIndexed { index, order ->
                        TextButton(
                            { onOrder(order.id) },
                            Modifier.fillMaxWidth()
                                .testTag(LiveMapTestTags.ORDER_PREFIX + order.id),
                        ) {
                          Text(
                              stringResource(
                                  R.string.live_map_view_order,
                                  index + 1,
                                  person.trips
                                      .firstOrNull { it.id == order.tripId }
                                      ?.store
                                      ?.name
                                      .orEmpty(),
                              )
                          )
                        }
                      }
                  else
                      person.trips.forEach { trip ->
                        val summary = trip.mapSummary()
                        TextButton(
                            { onTrip(trip.id) },
                            Modifier.fillMaxWidth().testTag(LiveMapTestTags.TRIP_PREFIX + trip.id),
                        ) {
                          Text(
                              stringResource(
                                  R.string.live_map_view_trip,
                                  summary.store,
                                  summary.time,
                                  summary.handoff,
                              )
                          )
                        }
                      }
                }
              }
        }
      }
    }
  }
}

/** Shared semantics for Compose, navigation and device tests. */
object LiveMapTestTags {
  const val SHARING = "live_position_sharing"
  const val SHARING_TOGGLE = "live_position_toggle"
  const val DISCOVERY = "live_map_discovery"
  const val TRACKING = "live_map_tracking"
  const val LOADING = "live_map_loading"
  const val RETRY = "live_map_retry"
  const val MARKER_PREFIX = "live_commando_marker_"
  const val PERSON_PREFIX = "live_commando_person_"
  const val TRIP_PREFIX = "live_commando_trip_"
  const val ORDER_PREFIX = "live_commando_order_"
}
