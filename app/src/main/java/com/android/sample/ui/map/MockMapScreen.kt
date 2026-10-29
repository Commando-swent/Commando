package com.android.sample.ui.map

// AI assistance: OpenAI Codex. UI prototype based on Figma section 421:2073.
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/** Floating card from Figma 424:2673 / 424:3003, shared by real trips and previews. */
@Composable
internal fun MapTripPreview(
    trip: MockMapTrip,
    group: List<MockMapTrip>,
    onSelectTrip: (String) -> Unit,
    onClose: () -> Unit,
    onDetails: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
  MapFloatingSurface(
      modifier.testTag(Tags.SUMMARY).semantics { liveRegion = LiveRegionMode.Polite }
  ) {
    Column(
        Modifier.then(
                if (trip.isSample) Modifier else Modifier.verticalScroll(rememberScrollState())
            )
            .padding(start = 19.dp, end = 19.dp, top = 17.dp, bottom = 19.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      if (group.size > 1) MapSharedPointPager(group, trip.id, onSelectTrip)
      Row(
          Modifier.fillMaxWidth().heightIn(min = 44.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Box(
            Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
          Text(
              trip.store.take(1).uppercase(),
              fontSize = 17.sp,
              lineHeight = 21.sp,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.onPrimary,
          )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
              trip.store,
              fontSize = 17.sp,
              lineHeight = 22.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
          Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
                painterResource(R.drawable.ic_trip_calendar),
                null,
                Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (trip.isSample) stringResource(R.string.mock_map_time, trip.time) else trip.time,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
          }
        }
        Box(
            Modifier.size(32.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
          Image(
              painterResource(R.drawable.map_close),
              stringResource(R.string.mock_map_close),
              Modifier.size(16.dp),
          )
        }
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Row(
          Modifier.fillMaxWidth().heightIn(min = 36.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(
            Modifier.size(36.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
          Icon(
              painterResource(R.drawable.ic_trip_map_pin),
              null,
              Modifier.size(18.dp),
              tint = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(trip.handoff, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
          Text(
              stringResource(R.string.mock_map_handoff),
              fontSize = 12.sp,
              lineHeight = 15.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Surface(
            Modifier.widthIn(min = 59.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
          Text(
              trip.distanceLabel ?: stringResource(R.string.trip_map_distance_unknown),
              Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
              fontSize = 12.sp,
              lineHeight = 15.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
          )
        }
      }
      Button(
          onClick = { onDetails?.invoke() },
          modifier = Modifier.fillMaxWidth().height(52.dp).testTag(Tags.DETAILS),
          shape = RoundedCornerShape(28.dp),
          enabled = onDetails != null,
      ) {
        Text(
            stringResource(
                if (onDetails == null) R.string.mock_map_details_unavailable
                else R.string.mock_map_details
            ),
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Image(painterResource(R.drawable.map_arrow_right), null, Modifier.size(20.dp))
      }
    }
  }
}

/** Shared Figma chrome for the map's floating cards, header and loading pill. */
@Composable
internal fun MapFloatingSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable () -> Unit,
) {
  Surface(
      modifier,
      shape = RoundedCornerShape(cornerRadius),
      color = color,
      border = BorderStroke(1.dp, borderColor),
      shadowElevation = 10.dp,
      content = content,
  )
}

@Composable
internal fun MapPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  MapFloatingSurface(modifier) {
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
    modifier: Modifier = Modifier,
) {
  Row(
      modifier.fillMaxWidth().heightIn(min = 48.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
        stringResource(R.string.mock_map_shared, group.size),
        Modifier.weight(1f),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = .66.sp,
        fontWeight = FontWeight.Bold,
    )
    Row(
        Modifier.widthIn(max = 120.dp).horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      group.forEachIndexed { index, trip ->
        val selected = trip.id == selectedId
        val description =
            stringResource(
                if (trip.isSample) R.string.mock_map_page else R.string.trip_map_page,
                index + 1,
                group.size,
                trip.store,
            )
        Box(
            Modifier.size(48.dp)
                .testTag(Tags.PAGE_PREFIX + trip.id)
                .selectable(selected, role = Role.Tab, onClick = { onSelect(trip.id) })
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
          Box(
              Modifier.size(if (selected) 16.dp else 6.dp, 6.dp)
                  .background(
                      if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outline,
                      CircleShape,
                  )
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
