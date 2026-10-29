package com.android.sample.ui.map

// AI assistance: OpenAI Codex. UI prototype based on Figma section 421:2073.
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
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
