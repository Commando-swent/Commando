package com.android.sample.ui.map

// AI assistance: OpenAI Codex. UI prototype based on Figma section 421:2073.

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.android.sample.ui.navigation.NavigationTestTags

@Composable
fun MockMapScreen(
    viewModel: MockMapViewModel,
    onTripDetails: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
  val state by viewModel.uiState.collectAsState()
  Column(modifier.fillMaxSize()) {
    onBack?.let {
      TextButton(onClick = it, modifier = Modifier.testTag(NavigationTestTags.MAP_LIST)) {
        Text(stringResource(R.string.nav_trip_list))
      }
    }
    MockMapScreen(
        state,
        viewModel::selectTrip,
        viewModel::clearSelection,
        onTripDetails,
        Modifier.weight(1f),
        viewModel::retry,
        viewModel::enableSampleLocation,
    )
  }
}

/** Stateless screen. The host decides what to do with sample IDs; no real details lookup occurs. */
@Composable
fun MockMapScreen(
    state: MockMapUiState,
    onSelectTrip: (String) -> Unit,
    onClearSelection: () -> Unit,
    onTripDetails: ((String) -> Unit)?,
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
              val selectedInGroup = group.firstOrNull { it.id == state.selectedTrip?.id }
              val trip = selectedInGroup ?: group.first()
              val markerWidth = if (group.size > 1 && selectedInGroup == null) 124.dp else 96.dp
              MapMarker(
                  trip,
                  selected = selectedInGroup != null,
                  time = trip.time,
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
          MapTripPreview(
              trip,
              state.selectedGroup,
              onSelectTrip,
              onClearSelection,
              onTripDetails?.let { action -> { action(trip.id) } },
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
