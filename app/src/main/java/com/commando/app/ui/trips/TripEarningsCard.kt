package com.commando.app.ui.trips

// AI assistance: Claude Code.
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.ui.orders.money

/** Stable selectors for the owner's earnings card and its retry action. */
object TripEarningsTestTags {
  const val CARD = "trip_earnings_card"
  const val AMOUNT = "trip_earnings_amount"
  const val DELIVERED_COUNT = "trip_earnings_delivered_count"
  const val RETRY = "trip_earnings_retry"
}

private val EARNINGS_SPACING = 6.dp
private val EARNINGS_PROGRESS_SIZE = 16.dp
private val EARNINGS_PROGRESS_STROKE_WIDTH = 2.dp
private val RETRY_CONTENT_PADDING = 0.dp

/**
 * The trip owner's earnings [state]: the commission of the delivered orders in CHF and how many
 * there are, or a loading or error message. [onRetry] reloads only the earnings.
 */
@Composable
internal fun TripEarningsCard(state: TripEarningsUiState, onRetry: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  TripDetailsCard(Modifier.testTag(TripEarningsTestTags.CARD)) {
    Text(
        stringResource(R.string.trip_details_earnings_title),
        modifier = Modifier.semantics { heading() },
        color = colors.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Column(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(EARNINGS_SPACING),
    ) {
      when (state) {
        TripEarningsUiState.Loading ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EARNINGS_SPACING),
            ) {
              CircularProgressIndicator(
                  Modifier.size(EARNINGS_PROGRESS_SIZE),
                  strokeWidth = EARNINGS_PROGRESS_STROKE_WIDTH,
              )
              EarningsDetail(stringResource(R.string.trip_details_earnings_loading))
            }
        is TripEarningsUiState.Content -> {
          Text(
              money(state.earnings.totalCents),
              modifier = Modifier.testTag(TripEarningsTestTags.AMOUNT),
              color = colors.onSurface,
              style = MaterialTheme.typography.headlineSmall,
          )
          val count = state.earnings.deliveredCount
          EarningsDetail(
              pluralStringResource(R.plurals.trip_details_earnings_delivered, count, count),
              Modifier.testTag(TripEarningsTestTags.DELIVERED_COUNT),
          )
        }
        is TripEarningsUiState.Error -> {
          EarningsDetail(stringResource(R.string.trip_details_earnings_error))
          TextButton(
              onClick = onRetry,
              modifier = Modifier.testTag(TripEarningsTestTags.RETRY),
              contentPadding = PaddingValues(RETRY_CONTENT_PADDING),
          ) {
            Text(stringResource(R.string.trips_retry))
          }
        }
      }
    }
  }
}

@Composable
private fun EarningsDetail(text: String, modifier: Modifier = Modifier) {
  Text(
      text,
      modifier = modifier,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
  )
}
