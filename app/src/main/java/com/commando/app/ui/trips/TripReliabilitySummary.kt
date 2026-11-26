package com.commando.app.ui.trips

// AI assistance: OpenAI Codex. Reliability layout from Figma #398.
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.commando.app.R
import com.commando.app.data.repository.RatingError
import com.commando.app.data.repository.RatingResult
import com.commando.app.model.ReliabilitySummary
import java.text.NumberFormat

/** Stable selectors for reliability, independent of customer-star summary selectors. */
object TripReliabilityTestTags {
  const val SECTION = "trip_reliability_section"
  const val RETRY = "trip_reliability_retry"
}

// Figma #398: 20 dp shield, 8 dp heading gap, 4 dp between lines, 28 dp count inset.
private val SHIELD_SIZE = 20.dp
private val HEADING_GAP = 8.dp
private val LINE_GAP = 4.dp
private val COUNT_INSET = SHIELD_SIZE + HEADING_GAP
private val HEADING_FONT_SIZE = 13.sp // Figma reliability label and value.

/** Shows trusted outcomes separately from stars; null [state] means loading, never zero history. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TripReliabilitySummary(
    state: RatingResult<ReliabilitySummary>?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val summary = (state as? RatingResult.Success<ReliabilitySummary>)?.data
  val headingStyle = MaterialTheme.typography.bodySmall.copy(fontSize = HEADING_FONT_SIZE)
  val percentage = summary?.percentage
  val locale = LocalConfiguration.current.locales[0]
  val value =
      when {
        state == null -> stringResource(R.string.trip_details_reliability_loading)
        summary == null -> stringResource(R.string.trip_details_reliability_error)
        summary.evaluatedCount == 0L -> stringResource(R.string.trip_details_reliability_empty)
        percentage == null -> stringResource(R.string.trip_details_reliability_insufficient)
        else ->
            NumberFormat.getPercentInstance(locale)
                .apply { maximumFractionDigits = 0 }
                .format(percentage / 100.0)
      }
  Column(
      modifier.testTag(TripReliabilityTestTags.SECTION).semantics {
        liveRegion = LiveRegionMode.Polite
      },
      verticalArrangement = Arrangement.spacedBy(LINE_GAP),
  ) {
    Column(
        Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(LINE_GAP),
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(HEADING_GAP)) {
        Icon(
            painterResource(R.drawable.ic_trip_reliability),
            contentDescription = null,
            modifier = Modifier.size(SHIELD_SIZE),
            tint = colors.primary,
        )
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(HEADING_GAP),
            verticalArrangement = Arrangement.spacedBy(LINE_GAP),
        ) {
          Text(
              stringResource(R.string.trip_details_reliability_title),
              style = headingStyle.copy(fontWeight = FontWeight.SemiBold),
              color = colors.onSurface,
          )
          Text(
              value,
              style = headingStyle.copy(fontWeight = FontWeight.Bold),
              color = colors.primary,
          )
        }
      }
      if (summary != null) {
        val count = summary.evaluatedCount
        Text(
            pluralStringResource(
                R.plurals.trip_details_reliability_count,
                // Android selects plurals with Int; the displayed count retains its full Long.
                count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                NumberFormat.getIntegerInstance(locale).format(count),
            ),
            modifier = Modifier.padding(start = COUNT_INSET),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
      }
    }
    if (state is RatingResult.Error && state.error != RatingError.NotAuthenticated) {
      TextButton(onClick = onRetry, modifier = Modifier.testTag(TripReliabilityTestTags.RETRY)) {
        Text(stringResource(R.string.trips_retry))
      }
    }
  }
}
