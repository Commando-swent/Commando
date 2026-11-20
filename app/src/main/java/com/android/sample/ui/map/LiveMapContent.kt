package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.commando.app.model.Location

private val LIVE_MARKER_SIZE = 48.dp

/** Live people share the existing campus drawing, projection and pan/zoom camera. */
@Composable
internal fun LiveCommandoMarkers(
    people: List<LiveCommando>,
    camera: MapCamera,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier.fillMaxSize()) {
    people.forEachIndexed { index, person ->
      person.position?.let { fix ->
        CAMPUS_MAP_PROJECTION.project(Location("", fix.latitude, fix.longitude))?.let {
          val point = camera.project(it)
          if (point.inViewport) {
            FilledIconButton(
                { onSelect(person.ownerId) },
                Modifier.offset(
                        maxWidth * point.x.toFloat() - LIVE_MARKER_SIZE / 2,
                        maxHeight * point.y.toFloat() - LIVE_MARKER_SIZE / 2,
                    )
                    .size(LIVE_MARKER_SIZE)
                    .testTag(LiveMapTags.MARKER + person.ownerId),
            ) {
              Icon(
                  painterResource(R.drawable.ic_live_commando),
                  stringResource(R.string.live_map_commando, index + 1),
              )
            }
          }
        }
      }
    }
  }
}

/** Keeps every tracking order reachable, even when no coordinate can be projected. */
@Composable
internal fun LiveMapSummary(
    state: LiveMapState,
    selectedOwner: String?,
    onClearSelection: () -> Unit,
    onTripDetails: (String) -> Unit,
    onOrderDetails: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  MapFloatingSurface(modifier.testTag(LiveMapTags.SUMMARY)) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      when (state) {
        LiveMapState.Loading -> {
          Text(stringResource(R.string.live_map_loading))
          LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LiveMapState.Unavailable -> {
          Text(stringResource(R.string.live_map_error))
          TextButton(onRetry, Modifier.testTag(LiveMapTags.RETRY)) {
            Text(stringResource(R.string.live_map_retry))
          }
        }
        is LiveMapState.Content -> {
          Text(
              stringResource(
                  if (state.mode == LiveMapMode.TRACKING) R.string.live_map_tracking
                  else R.string.live_map_discovery
              ),
              style = MaterialTheme.typography.titleMedium,
          )
          if (state.criteriaProblem != null) Text(stringResource(R.string.live_map_filters))
          else if (state.commandos.isEmpty()) Text(stringResource(R.string.live_map_empty))
          if (selectedOwner != null)
              TextButton(onClearSelection, Modifier.testTag(LiveMapTags.ALL)) {
                Text(stringResource(R.string.live_map_all))
              }
          state.commandos.forEachIndexed { index, person ->
            if (selectedOwner == null || selectedOwner == person.ownerId) {
              Column(Modifier.fillMaxWidth().testTag(LiveMapTags.GROUP + person.ownerId)) {
                Text(
                    stringResource(R.string.live_map_commando, index + 1),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(
                        when {
                          person.position == null -> R.string.live_map_position_missing
                          CAMPUS_MAP_PROJECTION.project(
                              Location("", person.position.latitude, person.position.longitude)
                          ) == null -> R.string.order_tracking_off_map
                          else -> R.string.order_tracking_live
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.mode == LiveMapMode.TRACKING)
                    person.orders.forEach { order ->
                      TextButton(
                          { onOrderDetails(order.id) },
                          Modifier.testTag(LiveMapTags.ORDER + order.id),
                      ) {
                        Text(stringResource(R.string.live_map_order, order.id.take(8)))
                      }
                    }
                else
                    person.trips.forEach { trip ->
                      TextButton(
                          { onTripDetails(trip.id) },
                          Modifier.testTag(LiveMapTags.TRIP + trip.id),
                      ) {
                        Text(
                            stringResource(
                                R.string.live_map_trip,
                                trip.store.name,
                                trip.handoffLocation.name,
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
}

/** Stable selectors; full IDs stay in callbacks and semantics, never shortened for navigation. */
object LiveMapTags {
  const val SUMMARY = "live_map_summary"
  const val MARKER = "live_map_marker_"
  const val GROUP = "live_map_group_"
  const val ORDER = "live_map_order_"
  const val TRIP = "live_map_trip_"
  const val RETRY = "live_map_retry"
  const val ALL = "live_map_all"
}
