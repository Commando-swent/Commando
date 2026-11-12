package com.android.sample.ui.map

// AI assistance: OpenAI Codex. Layout: Figma 424:2112, 424:2408 and 424:3648–424:3917.
// Claude Code (search feedback states).
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.sample.R
import com.android.sample.data.location.DeviceLocationResult
import com.android.sample.model.CommandoPosition
import com.android.sample.model.DeviceLocation
import com.android.sample.model.HANDOFF_RADIUS_METERS
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.canIdentifyNearbyTrips
import com.android.sample.model.distanceMeters
import com.android.sample.model.distanceTo
import com.android.sample.model.hasValidCoordinates
import com.android.sample.model.nearbyTrips
import com.android.sample.ui.components.formatTripTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Geographic extent shared by the bundled drawing and the marker projection. */
internal val CAMPUS_MAP_PROJECTION = CampusMapProjection(46.516, 46.526, 6.559, 6.573)

private val DEVICE_POSITION_DIAMETER = 24.dp
private val DEVICE_POSITION_INSET = 5.dp
private const val DEVICE_POSITION_HALO_ALPHA = 0.25f

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
    locationState: MapLocationUiState = MapLocationUiState.Idle,
    onLocate: (() -> Unit)? = null,
    tracking: OrderTrackingState? = null,
    mapSurface: @Composable (Modifier) -> Unit = { CampusMapBackground(it) },
) {
  val trackedFix = (tracking as? OrderTrackingState.Position)?.fix
  val content = state as? TripMapUiState.Content
  val groups = remember(content?.trips) { content?.markerGroups.orEmpty() }
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val locationMessage = stringResource(R.string.trip_map_location_unavailable)
  val fix =
      ((locationState as? MapLocationUiState.Resolved)?.result as? DeviceLocationResult.Available)
          ?.location
  var camera by rememberSaveable(stateSaver = MapCamera.Saver) { mutableStateOf(MapCamera()) }
  CenterTrackingPosition(trackedFix) { camera = MapCamera.centeredAt(it, LOCATION_CAMERA_ZOOM) }
  var lastCenteredFixTimestamp by rememberSaveable { mutableStateOf<Long?>(null) }
  LaunchedEffect(fix) {
    if (fix == null) lastCenteredFixTimestamp = null
    else if (lastCenteredFixTimestamp != fix.elapsedRealtimeMillis) {
      fix.campusPosition()?.let { camera = MapCamera.centeredAt(it, LOCATION_CAMERA_ZOOM) }
      lastCenteredFixTimestamp = fix.elapsedRealtimeMillis
    }
  }
  val nearby =
      remember(content?.trips, fix) {
        fix?.let { nearbyTrips(content?.trips.orEmpty(), it) }.orEmpty()
      }
  val feedback = mapLocationFeedback(locationState)
  LaunchedEffect(feedback) { if (feedback != null) snackbar.showSnackbar(feedback) }
  val uri = LocalUriHandler.current
  val density = LocalDensity.current
  var viewportBounds by remember { mutableStateOf(Rect.Zero) }
  var controlsBounds by remember { mutableStateOf(Rect.Zero) }
  var selectorBounds by
      remember(groups, viewportBounds, controlsBounds, camera) { mutableStateOf(Rect.Zero) }
  val markerBounds = remember(groups, camera) { mutableStateMapOf<String, Rect>() }
  BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    val showLocateWithCard = maxHeight >= 400.dp
    val controlsHeight = with(density) { controlsBounds.height.toDp() }
    val cardLimit = (maxHeight - controlsHeight - 32.dp).coerceAtLeast(0.dp)
    val locate: @Composable () -> Unit = {
      MapRoundAction(
          R.drawable.map_locate,
          if (tracking == null) R.string.trip_map_locate else R.string.order_tracking_recenter,
          {
            if (tracking != null) {
              trackedFix
                  ?.let { CAMPUS_MAP_PROJECTION.project(Location("", it.latitude, it.longitude)) }
                  ?.let { camera = MapCamera.centeredAt(it, LOCATION_CAMERA_ZOOM) }
            } else if (onLocate != null) onLocate()
            else scope.launch { snackbar.showSnackbar(locationMessage) }
          },
          Modifier.testTag(TripMapTestTags.LOCATE),
      )
    }
    MapTripCanvas(
        groups,
        content?.selectedTripId,
        selectedPoint,
        onSelectTrip,
        mapSurface,
        fix,
        camera,
        { dx, dy, zoom -> camera = camera.moved(dx, dy, zoom) },
        onViewportBounds = { viewportBounds = it },
        trackedPosition = (tracking as? OrderTrackingState.Position)?.fix,
    ) { id, bounds ->
      markerBounds[id] = bounds
    }
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
    val subtitle = trackingSubtitle(tracking) ?: mapSubtitle(state, fix, nearby.size)
    Column(
        Modifier.fillMaxWidth().padding(16.dp).onGloballyPositioned {
          controlsBounds = it.boundsInRoot()
        }
    ) {
      MapDiscoveryHeader(
          subtitle,
          onBack,
          onRefresh,
          state != TripMapUiState.Loading && state != TripMapUiState.SessionEnded,
          Modifier.fillMaxWidth(),
          tracking = tracking != null,
      )
      fix?.let {
        Text(
            stringResource(R.string.map_location_position, it.accuracyMeters.roundToInt()),
            style = MaterialTheme.typography.labelSmall,
        )
      }
      selectedPoint?.let {
        TextButton(onClearPoint, Modifier.testTag(TripMapTestTags.CLEAR_FILTER)) {
          Text(stringResource(R.string.trip_map_clear_filter, it.name), fontSize = 12.sp)
        }
      }
    }
    if (
        state == TripMapUiState.Empty ||
            state is TripMapUiState.NoMatches ||
            state is TripMapUiState.InvalidCriteria ||
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
        val offMap = groups.filter { group ->
          CAMPUS_MAP_PROJECTION.project(group.first().handoffLocation)?.let {
            camera.project(it).inViewport
          } != true ||
              markerBounds[group.first().id]?.let { bounds ->
                bounds.overlaps(controlsBounds) ||
                    bounds.overlaps(selectorBounds) ||
                    bounds.left < viewportBounds.left ||
                    bounds.top < viewportBounds.top ||
                    bounds.right > viewportBounds.right ||
                    bounds.bottom > viewportBounds.bottom
              } == true
        }
        if (offMap.isNotEmpty() && state.selectedTrip == null) {
          Surface(
              Modifier.align(Alignment.BottomCenter)
                  .padding(16.dp, 60.dp)
                  .heightIn(max = cardLimit)
                  .onGloballyPositioned { selectorBounds = it.boundsInRoot() },
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
          Column(
              Modifier.align(Alignment.BottomCenter)
                  .padding(16.dp)
                  .fillMaxWidth()
                  .heightIn(max = cardLimit),
              horizontalAlignment = Alignment.End,
              verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            if (showLocateWithCard) locate()
            MapTripPreview(
                trip.mapSummary(selectedPoint = selectedPoint, devicePosition = fix),
                state.selectedGroup.map {
                  it.mapSummary(selectedPoint = selectedPoint, devicePosition = fix)
                },
                onSelectTrip,
                onClearSelection,
                { onTripDetails(trip.id) },
                Modifier.fillMaxWidth().weight(1f, fill = false),
            )
          }
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
    if (content?.selectedTrip == null) {
      Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) { locate() }
    }
    SnackbarHost(
        snackbar,
        Modifier.align(Alignment.TopCenter).padding(top = controlsHeight + 16.dp),
    )
  }
}

/** Centers only the first tracking fix; later updates preserve the user's camera. */
@Composable
private fun CenterTrackingPosition(fix: CommandoPosition?, onCenter: (MapPosition) -> Unit) {
  var centered by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(fix) {
    if (!centered && fix != null) {
      CAMPUS_MAP_PROJECTION.project(Location("", fix.latitude, fix.longitude))?.let {
        onCenter(it)
        centered = true
      }
    }
  }
}

@Composable
private fun trackingSubtitle(state: OrderTrackingState?): String? {
  if (state == null) return null
  return stringResource(
      when (state) {
        OrderTrackingState.Loading -> R.string.order_tracking_loading
        OrderTrackingState.Unavailable -> R.string.order_tracking_error
        is OrderTrackingState.Position ->
            when {
              state.fix == null -> R.string.order_tracking_unavailable
              CAMPUS_MAP_PROJECTION.project(
                  Location("", state.fix.latitude, state.fix.longitude)
              ) == null -> R.string.order_tracking_off_map
              else -> R.string.order_tracking_live
            }
      }
  )
}

/** Header subtitle shared by the loading, error and location-aware discovery states. */
@Composable
private fun mapSubtitle(
    state: TripMapUiState,
    fix: DeviceLocation?,
    nearbyCount: Int,
): String =
    when (state) {
      TripMapUiState.Loading -> stringResource(R.string.trip_map_loading_subtitle)
      is TripMapUiState.Error -> stringResource(R.string.trip_map_error_subtitle)
      else -> {
        val content = state as? TripMapUiState.Content
        if (fix?.canIdentifyNearbyTrips() == true && content != null)
            pluralStringResource(
                R.plurals.map_location_nearby_count,
                nearbyCount,
                nearbyCount,
                HANDOFF_RADIUS_METERS.toInt(),
            )
        else {
          val tripCount = content?.trips?.size ?: 0
          pluralStringResource(R.plurals.trip_map_count, tripCount, tripCount)
        }
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
    devicePosition: DeviceLocation? = null,
): MapTripSummary {
  val locale = LocalConfiguration.current.locales[0]
  val departure =
      formatTripTime(
          scheduledAt,
          now = Instant.now(),
          zone = ZoneId.systemDefault(),
          locale = locale,
          today = stringResource(R.string.trip_today),
          tomorrow = stringResource(R.string.trip_tomorrow),
      )
  val meters =
      remember(selectedPoint, handoffLocation) {
        selectedPoint
            ?.takeIf { it.hasValidCoordinates() && handoffLocation.hasValidCoordinates() }
            ?.let { distanceMeters(it, handoffLocation).roundToInt() }
      }
  val deviceMeters = remember(devicePosition, handoffLocation) { devicePosition?.distanceTo(this) }
  val distance =
      if (deviceMeters != null)
          stringResource(
              if (
                  devicePosition?.canIdentifyNearbyTrips() == true &&
                      deviceMeters <= HANDOFF_RADIUS_METERS
              )
                  R.string.map_location_nearby_distance
              else R.string.map_location_distance,
              deviceMeters.roundToInt(),
          )
      else meters?.let { stringResource(R.string.trip_map_distance, it) }
  return MapTripSummary(
      id,
      store.name,
      departure,
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
    devicePosition: DeviceLocation?,
    camera: MapCamera,
    onCameraChange: (Double, Double, Float) -> Unit,
    onViewportBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    trackedPosition: CommandoPosition? = null,
    onMarkerBounds: (String, Rect) -> Unit,
) {
  BoxWithConstraints(
      modifier
          .fillMaxSize()
          .clipToBounds()
          .testTag(TripMapTestTags.SURFACE)
          .pointerInput(devicePosition) {
            detectTransformGestures { _, pan, zoom, _ ->
              onCameraChange(
                  pan.x.toDouble() / size.width,
                  pan.y.toDouble() / size.height,
                  zoom,
              )
            }
          }
          .onGloballyPositioned { onViewportBounds(it.boundsInRoot()) }
  ) {
    mapSurface(
        Modifier.matchParentSize().graphicsLayer {
          scaleX = camera.zoom
          scaleY = camera.zoom
          translationX = ((.5 - camera.center.x) * camera.zoom * size.width).toFloat()
          translationY = ((.5 - camera.center.y) * camera.zoom * size.height).toFloat()
        }
    )
    trackedPosition?.let { fix ->
      CAMPUS_MAP_PROJECTION.project(Location("", fix.latitude, fix.longitude))?.let { point ->
        val projected = camera.project(point)
        if (projected.inViewport) {
          Icon(
              painterResource(R.drawable.ic_live_commando),
              stringResource(R.string.order_tracking_live),
              Modifier.offset(
                      maxWidth * projected.x.toFloat() - DEVICE_POSITION_DIAMETER / 2,
                      maxHeight * projected.y.toFloat() - DEVICE_POSITION_DIAMETER / 2,
                  )
                  .size(DEVICE_POSITION_DIAMETER)
                  .testTag(OrderTrackingTestTags.MARKER),
              tint = MaterialTheme.colorScheme.tertiary,
          )
        }
      }
    }
    devicePosition?.campusPosition()?.let { point ->
      val projected = camera.project(point)
      if (!projected.inViewport) return@let
      val description =
          stringResource(R.string.map_location_position, devicePosition.accuracyMeters.roundToInt())
      Box(
          Modifier.offset(
                  maxWidth * projected.x.toFloat() - DEVICE_POSITION_DIAMETER / 2,
                  maxHeight * projected.y.toFloat() - DEVICE_POSITION_DIAMETER / 2,
              )
              .size(DEVICE_POSITION_DIAMETER)
              .background(
                  MaterialTheme.colorScheme.primary.copy(alpha = DEVICE_POSITION_HALO_ALPHA),
                  CircleShape,
              )
              .padding(DEVICE_POSITION_INSET)
              .background(MaterialTheme.colorScheme.primary, CircleShape)
              .testTag(MapLocationTestTags.POSITION)
              .semantics { contentDescription = description }
      )
    }
    groups.forEach { group ->
      val trip = group.firstOrNull { it.id == selectedTripId } ?: group.first()
      CAMPUS_MAP_PROJECTION.project(trip.handoffLocation)?.let { original ->
        val position = camera.project(original)
        if (!position.inViewport) return@let
        val summary = trip.mapSummary(position, selectedPoint, devicePosition)
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
            Modifier.offset(x, maxHeight * summary.y)
                .layout { measurable, constraints ->
                  val marker = measurable.measure(constraints)
                  layout(marker.width, marker.height) {
                    marker.place(-marker.width / 2, -marker.height)
                  }
                }
                .onGloballyPositioned {
                  onMarkerBounds(
                      group.first().id,
                      it.findRootCoordinates().localBoundingBoxOf(it, clipBounds = false),
                  )
                },
            labelShift = x.coerceIn(half, maxWidth - half) - x,
        )
      }
    }
  }
}
