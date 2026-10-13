package com.android.sample.ui.map

// AI assistance: OpenAI Codex. Additional Figma states, simulated entirely in memory.
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.sample.R

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
        Box(
            Modifier.size(48.dp)
                .testTag(MockMapTestTags.PAGE_PREFIX + trip.id)
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
  val background = Color(0xFF4D3A1E)
  val foreground = Color(0xFFF8DDB4)
  val accent = Color(0xFFF0C58A)
  Surface(
      modifier.fillMaxWidth().testTag(MockMapTestTags.LOCATION_OFF),
      shape = RoundedCornerShape(18.dp),
      color = background,
      contentColor = foreground,
      border = BorderStroke(1.dp, accent.copy(alpha = .35f)),
  ) {
    Row(
        Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
          painterResource(R.drawable.map_locate_off),
          null,
          Modifier.size(20.dp),
          tint = foreground,
      )
      Column(Modifier.weight(1f)) {
        Text(
            stringResource(R.string.mock_map_location_off),
            style = MaterialTheme.typography.labelMedium,
            fontSize = 13.sp,
        )
        Text(
            stringResource(R.string.mock_map_location_body),
            style = MaterialTheme.typography.bodySmall,
        )
      }
      val allowDescription = stringResource(R.string.mock_map_allow_description)
      Button(
          onClick = { onAllow?.invoke() },
          enabled = onAllow != null,
          modifier =
              Modifier.testTag(MockMapTestTags.ALLOW).semantics {
                contentDescription = allowDescription
              },
          contentPadding = PaddingValues(horizontal = 14.dp),
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = accent,
                  contentColor = MaterialTheme.colorScheme.onPrimary,
              ),
      ) {
        Text(stringResource(R.string.mock_map_allow), fontSize = 13.sp)
      }
    }
  }
}

@Composable
internal fun MapLoadingState(modifier: Modifier) {
  val transition = rememberInfiniteTransition(label = "Mock map loading")
  val angle by
      transition.animateFloat(
          0f,
          360f,
          infiniteRepeatable(tween(1000, easing = LinearEasing)),
          label = "Loading rotation",
      )
  BoxWithConstraints(modifier) {
    val width = maxWidth
    val height = maxHeight
    MockMapSamples.trips
        .distinctBy { it.handoffPointId }
        .forEach { trip ->
          Box(
              Modifier.offset(width * trip.x - 39.dp, height * trip.y - 24.dp)
                  .size(78.dp, 34.dp)
                  .background(
                      MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .8f),
                      CircleShape,
                  )
          )
        }
    Surface(
        Modifier.align(Alignment.TopCenter)
            .padding(top = 128.dp)
            .testTag(MockMapTestTags.LOADING)
            .semantics {
              progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
              liveRegion = LiveRegionMode.Polite
            },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Row(
          Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Image(painterResource(R.drawable.map_loader), null, Modifier.size(18.dp).rotate(angle))
        Text(
            stringResource(R.string.mock_map_loading),
            style = MaterialTheme.typography.labelMedium,
            fontSize = 13.sp,
        )
      }
    }
  }
}

@Composable
internal fun MapErrorCard(onRetry: (() -> Unit)?, modifier: Modifier) {
  Surface(
      modifier.fillMaxWidth().testTag(MockMapTestTags.ERROR).semantics {
        liveRegion = LiveRegionMode.Polite
      },
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
        Image(painterResource(R.drawable.map_error), null, Modifier.size(28.dp))
      }
      Text(
          stringResource(R.string.mock_map_error_title),
          style = MaterialTheme.typography.titleMedium,
          fontSize = 21.sp,
          lineHeight = 28.sp,
          textAlign = TextAlign.Center,
      )
      Text(
          stringResource(R.string.mock_map_error_body),
          style = MaterialTheme.typography.bodySmall,
          fontSize = 14.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
      Button(
          onClick = { onRetry?.invoke() },
          enabled = onRetry != null,
          modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(MockMapTestTags.RETRY),
      ) {
        Text(stringResource(R.string.mock_map_retry))
      }
    }
  }
}
