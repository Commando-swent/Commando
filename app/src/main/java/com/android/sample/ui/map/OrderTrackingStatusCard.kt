package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.sample.R

/** Readable tracking feedback with one contextual action, including on compact screens. */
@Composable
internal fun OrderTrackingStatusCard(
    state: OrderTrackingPresentation,
    onRecenter: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Card(
      modifier.fillMaxWidth().testTag(OrderTrackingStatusTags.CARD),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
      shape = MaterialTheme.shapes.large,
  ) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Icon(
            painterResource(R.drawable.ic_live_commando),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint =
                if (state.message == R.string.order_tracking_error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(state.message),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleSmall,
        )
      }
      if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
      state.ageSeconds?.let { seconds ->
        Text(
            pluralStringResource(R.plurals.order_tracking_age, seconds, seconds),
            Modifier.testTag(OrderTrackingStatusTags.AGE),
            style = MaterialTheme.typography.bodyMedium,
        )
      }
      state.accuracyMeters?.let { meters ->
        Text(
            stringResource(R.string.order_tracking_accuracy, meters),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (state.canRecenter || state.canRetry) {
        Button(
            onClick = if (state.canRecenter) onRecenter else onRetry,
            modifier =
                Modifier.fillMaxWidth()
                    .testTag(
                        if (state.canRecenter) TripMapTestTags.LOCATE
                        else OrderTrackingStatusTags.RETRY
                    ),
        ) {
          Text(
              stringResource(
                  if (state.canRecenter) R.string.order_tracking_recenter
                  else R.string.order_tracking_retry
              )
          )
        }
      }
    }
  }
}

/** Stable selectors for tracking feedback and contextual actions. */
internal object OrderTrackingStatusTags {
  const val CARD = "order_tracking_status_card"
  const val AGE = "order_tracking_position_age"
  const val RETRY = "order_tracking_retry"
}
