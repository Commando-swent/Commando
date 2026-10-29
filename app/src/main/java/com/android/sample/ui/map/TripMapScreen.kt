package com.android.sample.ui.map

// AI assistance: OpenAI Codex. Layout: Figma 424:2112, 424:2408 and 424:3648–424:3917.
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.sample.R
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.distanceMeters
import com.android.sample.model.hasValidCoordinates
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Geographic extent shared by the bundled drawing and the marker projection. */
internal val CAMPUS_MAP_PROJECTION = CampusMapProjection(46.516, 46.526, 6.559, 6.573)

/** Full-viewport map with the Figma header, list/map switch and floating trip/state cards. */
@Composable
fun TripMapScreen(
    state: TripMapUiState,
    onRefresh: () -> Unit,
    onSelectTrip: (String) -> Unit,
    onClearSelection: () -> Unit,
    onTripDetails: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    selectedPoint: Location? = null,
    onClearPoint: () -> Unit = {},
    mapSurface: @Composable (Modifier) -> Unit = { CampusMapBackground(it) },
) {
  val content = state as? TripMapUiState.Content
  val groups = remember(content?.trips) { content?.markerGroups.orEmpty() }
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val locationMessage = stringResource(R.string.trip_map_location_unavailable)
  val uri = LocalUriHandler.current
  BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    val cardLimit = (maxHeight - 110.dp).coerceAtLeast(160.dp)
    MapTripCanvas(groups, content?.selectedTripId, selectedPoint, onSelectTrip, mapSurface)
    Box(
        Modifier.fillMaxWidth()
            .height(186.dp)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background.copy(alpha = .92f),
                        Color.Transparent,
                    )
                )
            )
    )
    val tripCount = content?.trips?.size ?: 0
    val subtitle =
        when (state) {
          TripMapUiState.Loading -> stringResource(R.string.trip_map_loading_subtitle)
          is TripMapUiState.Error -> stringResource(R.string.trip_map_error_subtitle)
          else -> pluralStringResource(R.plurals.trip_map_count, tripCount, tripCount)
        }
    MapDiscoveryHeader(
        subtitle,
        onBack,
        onRefresh,
        state != TripMapUiState.Loading && state != TripMapUiState.SessionEnded,
        Modifier.fillMaxWidth().padding(16.dp),
    )
    selectedPoint?.let {
      TextButton(
          onClearPoint,
          Modifier.padding(start = 16.dp, top = 100.dp).testTag(TripMapTestTags.CLEAR_FILTER),
      ) {
        Text(stringResource(R.string.trip_map_clear_filter, it.name), fontSize = 12.sp)
      }
    }
    if (
        state == TripMapUiState.Empty ||
            state is TripMapUiState.NoMatches ||
            state is TripMapUiState.Error ||
            state == TripMapUiState.SessionEnded
    ) {
      Box(
          Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = .32f))
      )
    }
    when (state) {
      TripMapUiState.Loading -> {
        MapMarkerSkeletons(Modifier.matchParentSize())
        MapLoadingPill(
            Modifier.align(Alignment.TopCenter).padding(top = 128.dp.coerceAtMost(maxHeight / 3))
        )
      }
      is TripMapUiState.Content -> {
        val offMap = groups.filter {
          CAMPUS_MAP_PROJECTION.project(it.first().handoffLocation) == null
        }
        if (offMap.isNotEmpty() && state.selectedTrip == null) {
          Surface(
              Modifier.align(Alignment.BottomCenter)
                  .padding(16.dp, 60.dp)
                  .heightIn(max = cardLimit),
              shape = RoundedCornerShape(24.dp),
              color = MaterialTheme.colorScheme.surfaceContainer,
          ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
              offMap.forEach { group ->
                val trip = group.first()
                TextButton(
                    { onSelectTrip(trip.id) },
                    Modifier.testTag(TripMapTestTags.OFF_MAP_PREFIX + trip.id),
                ) {
                  Text(
                      stringResource(
                          R.string.trip_map_off_map,
                          trip.store.name,
                          trip.handoffLocation.name,
                      )
                  )
                }
              }
            }
          }
        }
        val trip = state.selectedTrip
        if (trip != null) {
          MapTripPreview(
              trip.mapSummary(selectedPoint = selectedPoint),
              state.selectedGroup.map { it.mapSummary(selectedPoint = selectedPoint) },
              onSelectTrip,
              onClearSelection,
              { onTripDetails(trip.id) },
              Modifier.align(Alignment.BottomCenter)
                  .padding(16.dp)
                  .fillMaxWidth()
                  .heightIn(max = cardLimit),
          )
        }
      }
      else ->
          MapFeedbackCard(
              state,
              onRefresh,
              Modifier.align(Alignment.Center)
                  .padding(horizontal = 24.dp)
                  .offset(
                      y =
                          if (maxHeight > 500.dp)
                              (if (state is TripMapUiState.Error) (-50).dp else (-47).dp)
                          else 0.dp
                  )
                  .heightIn(max = maxHeight - 16.dp),
          )
    }
    if (content?.selectedTrip == null) {
      TextButton(
          { uri.openUri("https://www.openstreetmap.org/copyright") },
          Modifier.align(Alignment.BottomStart),
      ) {
        Text(
            stringResource(R.string.trip_map_attribution),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (content?.selectedTrip == null || maxHeight >= 400.dp) {
      val locateBottom =
          if (content?.selectedTrip != null) {
            (if (content.selectedGroup.size > 1) 239.dp else 211.dp) + 32.dp
          } else 16.dp
      MapRoundAction(
          R.drawable.map_locate,
          R.string.trip_map_locate,
          { scope.launch { snackbar.showSnackbar(locationMessage) } },
          Modifier.align(Alignment.BottomEnd)
              .padding(end = 16.dp, bottom = locateBottom)
              .testTag(TripMapTestTags.LOCATE),
      )
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
  }
}

/** Geographical drawing and pins fill exactly the same viewport without independent cropping. */
@Composable
internal fun CampusMapBackground(modifier: Modifier = Modifier) {
  Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Image(
        painterResource(R.drawable.campus_map),
        stringResource(R.string.trip_map_north_up),
        Modifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.outlineVariant),
    )
  }
}

@Composable
private fun Trip.markerTime(): String {
  val locale = LocalConfiguration.current.locales[0]
  return remember(scheduledAt, locale) {
    DateTimeFormatter.ofPattern("HH:mm", locale)
        .withZone(ZoneId.systemDefault())
        .format(scheduledAt)
  }
}

/** Presentation only; distance calculation reuses the model's shared geodesic helper. */
@Composable
private fun Trip.mapSummary(
    position: MapPosition = MapPosition(0.0, 0.0),
    selectedPoint: Location? = null,
): MapTripSummary {
  val locale = LocalConfiguration.current.locales[0]
  val today = stringResource(R.string.trip_map_today)
  val tomorrow = stringResource(R.string.trip_map_tomorrow)
  val zone = ZoneId.systemDefault()
  val date = scheduledAt.atZone(zone).toLocalDate()
  val day =
      when (date) {
        LocalDate.now(zone) -> today
        LocalDate.now(zone).plusDays(1) -> tomorrow
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(date)
      }
  val time = markerTime()
  val meters =
      selectedPoint
          ?.takeIf { it.hasValidCoordinates() && handoffLocation.hasValidCoordinates() }
          ?.let { distanceMeters(it, handoffLocation).roundToInt() }
  val distance = meters?.let { stringResource(R.string.trip_map_distance, it) }
  return MapTripSummary(
      id,
      store.name,
      "$day, $time",
      handoffLocation.name,
      position.x.toFloat(),
      position.y.toFloat(),
      isSample = false,
      distanceLabel = distance,
  )
}

/** Draws only the geographic layer and real handoff markers, beneath the Figma overlays. */
@Composable
private fun MapTripCanvas(
    groups: List<List<Trip>>,
    selectedTripId: String?,
    selectedPoint: Location?,
    onSelectTrip: (String) -> Unit,
    mapSurface: @Composable (Modifier) -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().testTag(TripMapTestTags.SURFACE)) {
    mapSurface(Modifier.matchParentSize())
    groups.forEach { group ->
      val trip = group.firstOrNull { it.id == selectedTripId } ?: group.first()
      CAMPUS_MAP_PROJECTION.project(trip.handoffLocation)?.let { position ->
        val summary = trip.mapSummary(position, selectedPoint)
        val selected = trip.id == selectedTripId
        val x = maxWidth * summary.x
        val half =
            ((if (group.size > 1 && !selected) GROUP_MARKER_WIDTH else TRIP_MARKER_WIDTH) / 2)
                .coerceAtMost(maxWidth / 2)
        MapMarker(
            summary,
            selected,
            trip.markerTime(),
            group.size,
            { onSelectTrip(trip.id) },
            Modifier.offset(x, maxHeight * summary.y).layout { measurable, constraints ->
              val marker = measurable.measure(constraints)
              layout(marker.width, marker.height) {
                marker.place(-marker.width / 2, -marker.height)
              }
            },
            labelShift = x.coerceIn(half, maxWidth - half) - x,
        )
      }
    }
  }
}
