package com.commando.app.ui.trips

// AI assistance: OpenAI Codex. Creator-section layout from the existing #263 Figma frames.
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.commando.app.R
import com.commando.app.data.repository.RatingResult
import com.commando.app.model.ReliabilitySummary
import com.commando.app.ui.profile.ProfileAvatar
import com.commando.app.ui.theme.RatingAmber

/** Stable selectors for the creator's rating summary and its independent retry action. */
object TripRatingTestTags {
  const val SECTION = "trip_rating_section"
  const val SUMMARY = "trip_rating_summary"
  const val RETRY = "trip_rating_retry"
}

// Figma aligns the summary with the name: a 52 dp avatar followed by a 14 dp gap.
private val CREATOR_AVATAR_SIZE = 52.dp
private val CREATOR_GAP = 14.dp
private val CREATOR_TEXT_INSET = CREATOR_AVATAR_SIZE + CREATOR_GAP
private val DETAIL_FONT_SIZE = 13.sp
private val DETAIL_LINE_HEIGHT = 18.sp
private val CREATOR_LABEL_FONT_SIZE = 11.sp
private val CREATOR_LABEL_LETTER_SPACING = 0.8.sp
private val CREATOR_NAME_SPACING = 3.dp
private val CREATOR_NAME_FONT_SIZE = 16.sp
private val CREATOR_NAME_LINE_HEIGHT = 22.sp
private val RATING_SPACING = 6.dp
private val RATING_PROGRESS_SIZE = 16.dp
private val RATING_PROGRESS_STROKE_WIDTH = 2.dp
private val RATING_STAR_SIZE = 18.sp
private val RETRY_CONTENT_PADDING = 0.dp

/**
 * Shows the trip creator and overall rating [state]; [onRetry] reloads only the rating summary.
 * [commandoName] must belong to that creator; absent names use a generic label and neutral avatar.
 * The caller owns identity resolution and navigation; [reliabilityState] and [onRetryReliability]
 * provide independent reliability loading (null), results and retry below customer stars.
 */
@Composable
internal fun TripRatingSummary(
    state: TripRatingUiState,
    onRetry: () -> Unit,
    commandoName: String?,
    modifier: Modifier = Modifier,
    reliabilityState: RatingResult<ReliabilitySummary>? = null,
    onRetryReliability: () -> Unit = {},
) {
  val colors = MaterialTheme.colorScheme
  val typography = MaterialTheme.typography
  val locale = LocalConfiguration.current.locales[0]
  val detailStyle =
      typography.bodySmall.copy(fontSize = DETAIL_FONT_SIZE, lineHeight = DETAIL_LINE_HEIGHT)
  Column(
      modifier.fillMaxWidth().testTag(TripRatingTestTags.SECTION),
      verticalArrangement = Arrangement.spacedBy(CREATOR_GAP),
  ) {
    Text(
        stringResource(R.string.trip_details_created_by).uppercase(locale),
        style =
            typography.labelSmall.copy(
                fontSize = CREATOR_LABEL_FONT_SIZE,
                letterSpacing = CREATOR_LABEL_LETTER_SPACING,
            ),
        color = colors.onSurfaceVariant,
        modifier = Modifier.semantics { heading() },
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CREATOR_GAP),
    ) {
      // Constrain the existing avatar's extra action padding; the creator has no photo action.
      Box(Modifier.size(CREATOR_AVATAR_SIZE)) {
        ProfileAvatar(null, showPhotoAction = false, diameter = CREATOR_AVATAR_SIZE)
      }
      Column(
          Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(CREATOR_NAME_SPACING),
      ) {
        Text(
            commandoName?.takeUnless(String::isBlank) ?: stringResource(R.string.home_commando),
            style =
                typography.titleMedium.copy(
                    fontSize = CREATOR_NAME_FONT_SIZE,
                    lineHeight = CREATOR_NAME_LINE_HEIGHT,
                ),
            color = colors.onSurface,
        )
        Text(
            stringResource(R.string.trip_details_commando_member),
            style = detailStyle,
            color = colors.onSurfaceVariant,
        )
      }
    }
    Column(
        Modifier.fillMaxWidth()
            .padding(start = CREATOR_TEXT_INSET)
            .testTag(TripRatingTestTags.SUMMARY)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(RATING_SPACING),
    ) {
      when (state) {
        TripRatingUiState.Loading -> {
          Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(RATING_SPACING),
          ) {
            CircularProgressIndicator(
                Modifier.size(RATING_PROGRESS_SIZE),
                strokeWidth = RATING_PROGRESS_STROKE_WIDTH,
            )
            Text(
                stringResource(R.string.trip_details_rating_loading),
                style = detailStyle,
                color = colors.onSurfaceVariant,
            )
          }
        }
        is TripRatingUiState.Content -> {
          val summary = state.summary
          val average = String.format(locale, "%.1f", summary.averageScore)
          val count =
              pluralStringResource(
                  R.plurals.trip_details_rating_count,
                  summary.ratingCount,
                  summary.ratingCount,
              )
          val description = stringResource(R.string.trip_details_rating_description, average, count)
          Row(
              Modifier.clearAndSetSemantics { contentDescription = description },
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(RATING_SPACING),
          ) {
            Text("★", color = RatingAmber, fontSize = RATING_STAR_SIZE)
            Text(
                stringResource(R.string.trip_details_rating_value, average, count),
                style = detailStyle,
                color = colors.onSurfaceVariant,
            )
          }
        }
        TripRatingUiState.Empty ->
            Text(
                stringResource(R.string.trip_details_rating_empty),
                style = detailStyle,
                color = colors.onSurfaceVariant,
            )
        is TripRatingUiState.Error -> {
          Text(
              stringResource(R.string.trip_details_rating_error),
              style = detailStyle,
              color = colors.onSurfaceVariant,
          )
          TextButton(
              onClick = onRetry,
              modifier = Modifier.testTag(TripRatingTestTags.RETRY),
              contentPadding = PaddingValues(RETRY_CONTENT_PADDING),
          ) {
            Text(stringResource(R.string.trips_retry))
          }
        }
      }
    }
    TripReliabilitySummary(
        reliabilityState,
        onRetryReliability,
        Modifier.fillMaxWidth().padding(start = CREATOR_TEXT_INSET),
    )
  }
}
