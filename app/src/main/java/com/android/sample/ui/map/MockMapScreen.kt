package com.android.sample.ui.map

// AI assistance: OpenAI Codex. UI prototype based on Figma section 421:2073.
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.android.sample.R
import com.android.sample.ui.map.MockMapTestTags as Tags

@Composable
fun MockMapScreen(
    viewModel: MockMapViewModel,
    onTripDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  val state by viewModel.uiState.collectAsState()
  MockMapScreen(
      state,
      viewModel::selectTrip,
      viewModel::clearSelection,
      onTripDetails,
      modifier,
      viewModel::retry,
      viewModel::enableSampleLocation,
  )
}

/** Stateless screen. The host decides what to do with sample IDs; no real details lookup occurs. */
@Composable
fun MockMapScreen(
    state: MockMapUiState,
    onSelectTrip: (String) -> Unit,
    onClearSelection: () -> Unit,
    onTripDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onEnableSampleLocation: (() -> Unit)? = null,
) {
  BoxWithConstraints(modifier.fillMaxSize()) {
    val viewportHeight = maxHeight.coerceAtLeast(749.dp)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
      BoxWithConstraints(Modifier.fillMaxWidth().height(viewportHeight).clipToBounds()) {
        val mapWidth = maxWidth
        val mapHeight = maxHeight
        MapIllustration(Modifier.matchParentSize())
        MapPanel(Modifier.fillMaxWidth().padding(16.dp)) {
          Text(
              stringResource(R.string.mock_map_title),
              style = MaterialTheme.typography.titleMedium,
          )
          Text(
              stringResource(R.string.mock_map_subtitle),
              style = MaterialTheme.typography.bodySmall,
          )
        }
        if (state.status == MockMapStatus.Content)
            state.markerGroups.forEach { group ->
              val trip = group.first()
              val selectedInGroup = group.firstOrNull { it.id == state.selectedTrip?.id }
              val markerWidth = if (group.size > 1 && selectedInGroup == null) 124.dp else 96.dp
              MapMarker(
                  trip,
                  selected = selectedInGroup != null,
                  time = selectedInGroup?.time ?: trip.time,
                  groupSize = group.size,
                  onClick = { onSelectTrip(trip.id) },
                  modifier =
                      Modifier.offset(
                          x =
                              (mapWidth * trip.x - markerWidth / 2).coerceIn(
                                  0.dp,
                                  (mapWidth - markerWidth).coerceAtLeast(0.dp),
                              ),
                          y =
                              (mapHeight * trip.y - 24.dp + if (state.locationOff) 50.dp else 0.dp)
                                  .coerceIn(110.dp, mapHeight - 280.dp),
                      ),
              )
            }
        if (state.status == MockMapStatus.Loading) {
          MapLoadingState(state.trips, Modifier.matchParentSize())
        }
        if (state.status == MockMapStatus.Content && state.locationOff) {
          MapLocationBanner(
              onEnableSampleLocation,
              Modifier.align(Alignment.TopCenter).padding(top = 104.dp, start = 16.dp, end = 16.dp),
          )
        }
        if (!state.locationOff && state.status != MockMapStatus.Error) {
          Image(
              painterResource(R.drawable.map_sample_location),
              stringResource(R.string.mock_map_sample_location),
              Modifier.offset(mapWidth * 222f / 412, mapHeight * 328f / 749)
                  .size(56.dp)
                  .testTag(Tags.LOCATION),
          )
        }
        if (state.status == MockMapStatus.Error) {
          Box(
              Modifier.matchParentSize()
                  .background(MaterialTheme.colorScheme.background.copy(alpha = .5f))
          )
          MapErrorCard(
              onRetry,
              Modifier.align(Alignment.TopCenter).padding(top = 176.dp, start = 24.dp, end = 24.dp),
          )
        }
        if (state.status == MockMapStatus.Content && state.trips.isEmpty()) {
          EmptyMapCard(
              Modifier.align(Alignment.TopCenter).padding(top = 186.dp, start = 24.dp, end = 24.dp)
          )
        }
        Text(
            stringResource(R.string.mock_map_attribution),
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.selectedTrip?.let { trip ->
          TripPreview(
              trip,
              state.selectedGroup,
              onSelectTrip,
              onClearSelection,
              { onTripDetails(trip.id) },
              Modifier.align(Alignment.BottomCenter)
                  .fillMaxWidth()
                  .padding(start = 16.dp, end = 16.dp, bottom = 40.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun TripPreview(
    trip: MockMapTrip,
    group: List<MockMapTrip>,
    onSelectTrip: (String) -> Unit,
    onClose: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier,
) {
  MapPanel(modifier.testTag(Tags.SUMMARY).semantics { liveRegion = LiveRegionMode.Polite }) {
    if (group.size > 1) MapSharedPointPager(group, trip.id, onSelectTrip)
    ListItem(
        headlineContent = { Text(trip.store) },
        supportingContent = { Text(stringResource(R.string.mock_map_time, trip.time)) },
        leadingContent = { Icon(painterResource(R.drawable.ic_trip_shopping_bag), null) },
        trailingContent = {
          IconButton(onClose) {
            Image(
                painterResource(R.drawable.map_close),
                stringResource(R.string.mock_map_close),
                Modifier.size(16.dp),
            )
          }
        },
        colors =
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
    HorizontalDivider()
    ListItem(
        headlineContent = { Text(trip.handoff) },
        supportingContent = { Text(stringResource(R.string.mock_map_handoff)) },
        leadingContent = { Icon(painterResource(R.drawable.ic_trip_map_pin), null) },
        colors =
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
    Button(
        onDetails,
        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(Tags.DETAILS),
    ) {
      Text(stringResource(R.string.mock_map_details))
    }
  }
}

@Composable
internal fun MapPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
  Surface(
      modifier,
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.surfaceContainer,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      shadowElevation = 8.dp,
  ) {
    Column(
        Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
  }
}

@Composable
internal fun MapSharedPointPager(
    group: List<MockMapTrip>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
        stringResource(R.string.mock_map_shared, group.size),
        Modifier.weight(1f),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 11.sp,
    )
    Row(Modifier.selectableGroup()) {
      group.forEachIndexed { index, trip ->
        val selected = trip.id == selectedId
        val description = stringResource(R.string.mock_map_page, index + 1, group.size, trip.store)
        Tab(
            selected = selected,
            onClick = { onSelect(trip.id) },
            modifier =
                Modifier.size(48.dp).testTag(Tags.PAGE_PREFIX + trip.id).semantics {
                  contentDescription = description
                },
        ) {
          Box(
              Modifier.size(if (selected) 16.dp else 6.dp, 6.dp)
                  .background(MaterialTheme.colorScheme.primary, CircleShape)
          )
        }
      }
    }
  }
}

@Composable
internal fun MapLocationBanner(onAllow: (() -> Unit)?, modifier: Modifier) {
  Surface(modifier.testTag(Tags.LOCATION_OFF), shape = RoundedCornerShape(18.dp)) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.mock_map_location_off)) },
        supportingContent = { Text(stringResource(R.string.mock_map_location_body)) },
        trailingContent = {
          TextButton(
              onClick = { onAllow?.invoke() },
              enabled = onAllow != null,
              modifier = Modifier.testTag(Tags.ALLOW),
          ) {
            Text(stringResource(R.string.mock_map_allow))
          }
        },
        colors =
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                headlineColor = MaterialTheme.colorScheme.onTertiaryContainer,
                supportingColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
    )
  }
}

@Composable
internal fun MapLoadingState(trips: List<MockMapTrip>, modifier: Modifier) {
  BoxWithConstraints(modifier) {
    val width = maxWidth
    val height = maxHeight
    trips
        .distinctBy { it.handoffPointId }
        .forEach { trip ->
          Box(
              Modifier.testTag(Tags.SKELETON_PREFIX + trip.id)
                  .offset(width * trip.x - 39.dp, height * trip.y - 24.dp)
                  .size(78.dp, 34.dp)
                  .background(
                      MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .8f),
                      CircleShape,
                  )
          )
        }
    MapPanel(Modifier.align(Alignment.TopCenter).padding(top = 128.dp).testTag(Tags.LOADING)) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            stringResource(R.string.mock_map_loading),
            style = MaterialTheme.typography.labelMedium,
        )
      }
    }
  }
}

@Composable
internal fun MapErrorCard(onRetry: (() -> Unit)?, modifier: Modifier) {
  MapPanel(
      modifier.fillMaxWidth().testTag(Tags.ERROR).semantics { liveRegion = LiveRegionMode.Polite }
  ) {
    Icon(
        painterResource(R.drawable.ic_trip_alert_circle),
        null,
        Modifier.align(Alignment.CenterHorizontally).size(48.dp),
    )
    Text(
        stringResource(R.string.mock_map_error_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(stringResource(R.string.mock_map_error_body), style = MaterialTheme.typography.bodyMedium)
    Button(
        onClick = { onRetry?.invoke() },
        enabled = onRetry != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(Tags.RETRY),
    ) {
      Text(stringResource(R.string.mock_map_retry))
    }
  }
}
