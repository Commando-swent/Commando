package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.model.Location

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
  const val DISCOVERY = "live_map_discovery"
  const val TRACKING = "live_map_tracking"
  const val LOADING = "live_map_loading"
  const val RETRY = "live_map_retry"
  const val MARKER_PREFIX = "live_commando_marker_"
  const val PERSON_PREFIX = "live_commando_person_"
  const val TRIP_PREFIX = "live_commando_trip_"
  const val ORDER_PREFIX = "live_commando_order_"
}
