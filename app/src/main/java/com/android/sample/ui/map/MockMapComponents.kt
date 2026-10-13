package com.android.sample.ui.map

// AI assistance: OpenAI Codex. Reusable map components from Figma section 421:2073.
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
    trip: MockMapTrip,
    selected: Boolean,
    time: String,
    groupSize: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
  val description = stringResource(R.string.mock_map_marker, trip.store, trip.time)
  Column(
      modifier
          .width(if (groupSize > 1 && !selected) 124.dp else 96.dp)
          .heightIn(min = 48.dp)
          .testTag(MockMapTestTags.MARKER_PREFIX + trip.id)
          .selectable(selected, role = Role.Button, onClick = onClick)
          .semantics { contentDescription = description },
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Surface(
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
            painterResource(R.drawable.map_pin),
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
