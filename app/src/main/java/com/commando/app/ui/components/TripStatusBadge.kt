package com.commando.app.ui.components

// AI assistance: OpenAI Codex.
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.commando.app.R
import com.commando.app.model.Trip
import com.commando.app.model.TripStatus

/** The status badge tag is suffixed with the trip ID. */
internal object TripStatusBadgeTestTags {
  const val STATUS_PREFIX = "trip_status_"
}

/** Shows the stored status; closed trips use a muted style, as in the Figma. */
@Composable
internal fun TripStatusBadge(trip: Trip, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  val (label, active) =
      when (trip.status) {
        TripStatus.PUBLISHED -> R.string.my_trips_status_published to true
        TripStatus.IN_PROGRESS -> R.string.my_trips_status_in_progress to true
        TripStatus.COMPLETED -> R.string.my_trips_status_completed to false
        TripStatus.CANCELLED -> R.string.my_trips_status_cancelled to false
      }
  Text(
      stringResource(label),
      modifier
          .testTag(TripStatusBadgeTestTags.STATUS_PREFIX + trip.id)
          .background(
              if (active) colors.secondaryContainer else colors.surfaceContainerHigh,
              CircleShape,
          )
          .border(1.dp, colors.outline, CircleShape)
          .padding(horizontal = 10.dp, vertical = 2.dp),
      color = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
  )
}
