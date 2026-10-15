package com.android.sample.ui.components

// AI assistance: Claude Code. Shared by My Orders and My Trips.
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.ui.trips.formatTripDeparture
import java.time.Instant

/** Placeholder card shown while a list of trips or orders is loading. */
@Composable
internal fun SkeletonCard() {
  val colors = MaterialTheme.colorScheme
  Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
      border = BorderStroke(1.dp, colors.outlineVariant),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      listOf(0.6f, 0.25f, 0.5f, 0.65f).forEach { width ->
        Box(
            Modifier.fillMaxWidth(width)
                .height(16.dp)
                .background(colors.surfaceContainerHigh, RoundedCornerShape(8.dp))
        )
      }
    }
  }
}

/** Empty or error card for a list screen, with an optional help text and one action. */
@Composable
internal fun StateCard(
    tag: String,
    icon: Int,
    message: String,
    action: String,
    actionTag: String,
    onAction: () -> Unit,
    help: String? = null,
) {
  val colors = MaterialTheme.colorScheme
  Card(
      modifier =
          Modifier.fillMaxWidth().testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
      border = BorderStroke(1.dp, colors.outlineVariant),
  ) {
    // Scrolls so the action stays reachable on short screens or with large fonts.
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Box(
          Modifier.size(88.dp).background(colors.primary, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = colors.onPrimary,
        )
      }
      Text(
          message,
          color = colors.onSurface,
          style = MaterialTheme.typography.titleLarge,
          textAlign = TextAlign.Center,
      )
      if (help != null) {
        Text(
            help,
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
      }
      Button(
          onClick = onAction,
          modifier = Modifier.fillMaxWidth().height(56.dp).testTag(actionTag),
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = colors.primary,
                  contentColor = colors.onPrimary,
              ),
      ) {
        Text(action)
      }
    }
  }
}

/** Calendar icon followed by the trip departure, formatted for the device locale and zone. */
@Composable
internal fun TripDepartureRow(scheduledAt: Instant) {
  val colors = MaterialTheme.colorScheme
  val locale = LocalConfiguration.current.locales[0]
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
        painterResource(R.drawable.ic_trip_calendar),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = colors.onSurfaceVariant,
    )
    Text(
        formatTripDeparture(scheduledAt, locale),
        color = colors.onSurface,
        style = MaterialTheme.typography.bodySmall,
    )
  }
}
