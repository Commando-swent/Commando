package com.android.sample.ui.trips

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
import com.android.sample.R
import com.android.sample.ui.profile.ProfileAvatar
import com.android.sample.ui.theme.RatingAmber

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

/**
 * Shows the trip creator and overall rating [state]; [onRetry] reloads only the rating summary.
 * [commandoName] must belong to that creator; absent names use a generic label and neutral avatar.
 * The caller supplies state and owns all loading, identity resolution and navigation.
 */
@Composable
internal fun TripRatingSummary(
    state: TripRatingUiState,
    onRetry: () -> Unit,
    commandoName: String?,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val typography = MaterialTheme.typography
  val locale = LocalConfiguration.current.locales[0]
  val detailStyle = typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp)
  Column(
      modifier.fillMaxWidth().testTag(TripRatingTestTags.SECTION),
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text(
        stringResource(R.string.trip_details_created_by).uppercase(locale),
        style = typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.8.sp),
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
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            commandoName?.takeUnless(String::isBlank) ?: stringResource(R.string.home_commando),
            style = typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
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
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      when (state) {
        TripRatingUiState.Loading -> {
          Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
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
              horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text("★", color = RatingAmber, fontSize = 18.sp)
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
              contentPadding = PaddingValues(0.dp),
          ) {
            Text(stringResource(R.string.trips_retry))
          }
        }
      }
    }
  }
}
