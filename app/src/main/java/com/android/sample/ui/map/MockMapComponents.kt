package com.android.sample.ui.map

// AI assistance: OpenAI Codex. Reusable map components from Figma section 421:2073.
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.android.sample.R
import com.android.sample.ui.navigation.NavigationTestTags

internal val TRIP_MARKER_WIDTH = 96.dp
internal val GROUP_MARKER_WIDTH = 124.dp

@Composable
internal fun MapIllustration(modifier: Modifier) {
  val map = ImageBitmap.imageResource(R.drawable.mock_campus_map)
  BoxWithConstraints(modifier) {
    // The Figma map artwork spans the full 412 x 917 frame; bars cover y=0..64 and 813..917.
    // Crop those covered areas while keeping marker positions in the same illustrated coordinate
    // space.
    Canvas(Modifier.matchParentSize()) {
      drawImage(
          map,
          srcOffset = IntOffset(0, (map.height * 64f / 917).toInt()),
          srcSize = IntSize(map.width, (map.height * 749f / 917).toInt()),
          dstSize = IntSize(size.width.toInt(), size.height.toInt()),
      )
    }
    val width = maxWidth
    val height = maxHeight
    @Composable
    fun Label(text: String, x: Float, y: Float, size: Int, spacing: Float = .6f) {
      Text(
          text,
          Modifier.offset(width * x / 412, height * (y - 64) / 749).clearAndSetSemantics {},
          color = MaterialTheme.colorScheme.outline.copy(alpha = .65f),
          style = MaterialTheme.typography.labelSmall,
          fontSize = size.sp,
          letterSpacing = spacing.sp,
      )
    }
    Label("EPFL", 150f, 268f, 13, 3.12f)
    Label("UNIL · Dorigny", 40f, 662f, 10, 1f)
    Label("Route Cantonale", 248f, 566f, 9)
    Label("Ecublens", 268f, 118f, 10, 1f)
    Label("M1", 360f, 228f, 9)
  }
}

@Composable
internal fun MapMarker(
    trip: MapTripSummary,
    selected: Boolean,
    time: String,
    groupSize: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelShift: Dp = 0.dp,
) {
  val description =
      stringResource(
          if (trip.isSample) R.string.mock_map_marker else R.string.trip_map_marker,
          trip.store,
          time,
          trip.handoff,
      )
  Column(
      modifier
          .width(
              (if (groupSize > 1 && !selected) GROUP_MARKER_WIDTH else TRIP_MARKER_WIDTH) +
                  labelShift.coerceAtLeast(-labelShift) * 2
          )
          .heightIn(min = 48.dp)
          .testTag(MockMapTestTags.MARKER_PREFIX + trip.id)
          .selectable(selected, role = Role.Button, onClick = onClick)
          .semantics { contentDescription = description },
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Bottom,
  ) {
    Surface(
        modifier =
            Modifier.offset(x = labelShift).testTag(TripMapTestTags.MARKER_LABEL_PREFIX + trip.id),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 6.dp,
        color =
            if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
      Row(
          Modifier.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Box(
            Modifier.size(24.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
          Image(painterResource(R.drawable.map_bag), null, Modifier.size(14.dp))
        }
        Text(
            time,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
        )
        if (groupSize > 1 && !selected) {
          Text(
              "+${groupSize - 1}",
              Modifier.background(MaterialTheme.colorScheme.onPrimary, CircleShape)
                  .padding(horizontal = 5.dp),
              color = MaterialTheme.colorScheme.primary,
              style = MaterialTheme.typography.labelSmall,
          )
        }
      }
    }
    Image(
        painterResource(R.drawable.map_marker_tip),
        null,
        Modifier.size(10.4.dp, 6.dp).rotate(180f),
    )
  }
}

@Composable
internal fun EmptyMapCard(modifier: Modifier) {
  Surface(
      modifier.fillMaxWidth().testTag(MockMapTestTags.EMPTY),
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.secondaryContainer,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Column(
        Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Box(
          Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            painterResource(R.drawable.ic_trip_map_pin),
            null,
            Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
      }
      Text(
          stringResource(R.string.mock_map_empty_title),
          style = MaterialTheme.typography.titleMedium,
          textAlign = TextAlign.Center,
      )
      Text(
          stringResource(R.string.mock_map_empty_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
    }
  }
}

/** Stable semantics for the real map and its Figma overlays. */
object TripMapTestTags {
  const val MARKER_LABEL_PREFIX = "trip_map_marker_label_"
  const val HEADER = "trip_map_header"
  const val EMPTY_REFRESH = "trip_map_empty_refresh"
  const val REFRESH = "trip_map_refresh"
  const val LOCATE = "trip_map_locate"
  const val STATUS = "trip_map_status"
  const val CLEAR_FILTER = "trip_map_clear_filter"
  const val SURFACE = "trip_map_surface"
  const val OFF_MAP_PREFIX = "trip_map_off_map_"
}

/** Header matches Figma's 380 × 78 panel; bars remain owned by AppScaffold. */
@Composable
internal fun MapDiscoveryHeader(
    subtitle: String,
    onList: () -> Unit,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
  MapFloatingSurface(
      modifier.testTag(TripMapTestTags.HEADER),
      color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .96f),
  ) {
    Row(
        Modifier.height(78.dp).padding(start = 19.dp, end = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.mock_map_title),
            style = MaterialTheme.typography.titleMedium,
            fontSize = 21.sp,
            lineHeight = 28.sp,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Surface(
          shape = RoundedCornerShape(20.dp),
          color = MaterialTheme.colorScheme.background,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
          Box(
              Modifier.size(40.dp, 34.dp)
                  .clickable(role = Role.Button, onClick = onList)
                  .testTag(NavigationTestTags.MAP_LIST),
              contentAlignment = Alignment.Center,
          ) {
            Image(
                painterResource(R.drawable.map_list),
                stringResource(R.string.nav_trip_list),
                Modifier.size(20.dp),
            )
          }
          Box(
              Modifier.size(40.dp, 34.dp)
                  .clickable(enabled = refreshEnabled, role = Role.Button, onClick = onRefresh)
                  .testTag(TripMapTestTags.REFRESH)
                  .background(
                      MaterialTheme.colorScheme.secondaryContainer,
                      RoundedCornerShape(17.dp),
                  ),
              contentAlignment = Alignment.Center,
          ) {
            Image(
                painterResource(R.drawable.map_view),
                stringResource(R.string.trip_map_refresh_map),
                Modifier.size(20.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
internal fun MapRoundAction(
    icon: Int,
    description: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Surface(
      onClick,
      modifier.size(52.dp),
      shape = CircleShape,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      shadowElevation = 7.dp,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Image(painterResource(icon), stringResource(description), Modifier.size(24.dp))
    }
  }
}

@Composable
internal fun MapLoadingPill(modifier: Modifier = Modifier) {
  MapFloatingSurface(
      modifier.testTag(TripMapTestTags.STATUS),
      cornerRadius = 20.dp,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Row(
        Modifier.padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Image(painterResource(R.drawable.map_loader), null, Modifier.size(18.dp))
      Text(
          stringResource(R.string.trip_map_loading),
          style = MaterialTheme.typography.bodySmall,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
internal fun MapFeedbackCard(
    state: TripMapUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val error = state is TripMapUiState.Error
  val title =
      when (state) {
        is TripMapUiState.NoMatches ->
            stringResource(R.string.trips_no_matches, state.location.name)
        TripMapUiState.SessionEnded -> stringResource(R.string.trip_map_session_ended)
        is TripMapUiState.Error -> stringResource(R.string.mock_map_error_title)
        else -> stringResource(R.string.mock_map_empty_title)
      }
  MapFloatingSurface(
      modifier
          .fillMaxWidth()
          .heightIn(min = if (error) 298.dp else 283.dp)
          .testTag(TripMapTestTags.STATUS),
      color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .97f),
      borderColor = MaterialTheme.colorScheme.outline,
  ) {
    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(start = 25.dp, end = 25.dp, top = 29.dp, bottom = 25.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Box(
          Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            painterResource(
                if (error) R.drawable.ic_trip_alert_circle else R.drawable.ic_trip_map_pin
            ),
            null,
            Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
      }
      Text(
          title,
          style = MaterialTheme.typography.titleMedium,
          fontSize = 21.sp,
          lineHeight = 28.sp,
          textAlign = TextAlign.Center,
      )
      if (state != TripMapUiState.SessionEnded) {
        Text(
            stringResource(
                if (error) R.string.trip_map_error_body else R.string.trip_map_empty_body
            ),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (error)
            Button(
                onRefresh,
                Modifier.fillMaxWidth().height(56.dp).testTag(MockMapTestTags.RETRY),
            ) {
              Text(stringResource(R.string.trips_retry))
            }
        else
            OutlinedButton(
                onRefresh,
                Modifier.fillMaxWidth().height(49.dp).testTag(TripMapTestTags.EMPTY_REFRESH),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
              Text(
                  stringResource(R.string.trip_map_refresh),
                  fontSize = 15.sp,
                  color = MaterialTheme.colorScheme.onSurface,
              )
            }
      }
    }
  }
}

/**
 * Noninteractive placeholder geometry from Figma 424:3605–424:3608; these are not trip fixtures.
 */
@Composable
internal fun MapMarkerSkeletons(modifier: Modifier = Modifier) {
  val color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .85f)
  Canvas(modifier) {
    listOf(111f to 364f, 53f to 256f, 193f to 212f, 267f to 130f).forEach { (x, y) ->
      drawRoundRect(
          color,
          Offset(size.width * x / 412, size.height * y / 749),
          Size(78.dp.toPx(), 34.dp.toPx()),
          CornerRadius(17.dp.toPx()),
      )
    }
  }
}
