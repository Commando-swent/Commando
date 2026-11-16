package com.android.sample.ui.orders

// AI assistance: OpenAI Codex. Layout from the existing Figma rating dialog masters.
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.sample.R
import com.android.sample.data.repository.RatingError
import com.android.sample.ui.theme.RatingAmber
import com.commando.app.model.MAX_RATING_COMMENT_LENGTH

/** Stable selectors for the modal and its actions, independent of language. */
object RatingDialogTestTags {
  const val DIALOG = "rating_dialog"
  const val STAR_PREFIX = "rating_star_"
  const val COMMENT = "rating_comment"
  const val SUBMIT = "rating_submit"
  const val CANCEL = "rating_cancel"
}

/**
 * Renders editable or in-flight feedback; the caller owns validation, persistence and dismissal.
 */
@Composable
fun RatingDialog(
    state: RatingDialogState,
    onScore: (Int) -> Unit,
    onComment: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Dialog(
      onDismissRequest = { if (!state.submitting) onDismiss() },
      properties =
          DialogProperties(
              dismissOnBackPress = !state.submitting,
              dismissOnClickOutside = !state.submitting,
          ),
  ) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
      Column(
          Modifier.fillMaxWidth()
              .testTag(RatingDialogTestTags.DIALOG)
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 20.dp, vertical = 24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Text(
            stringResource(R.string.rating_title),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.rating_prompt),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = colors.onSurfaceVariant,
        )
        Row(Modifier.selectableGroup()) {
          RATING_SCORE_RANGE.forEach { score ->
            val label = stringResource(R.string.rating_star, score, RATING_SCORE_RANGE.last)
            Box(
                Modifier.size(48.dp)
                    .testTag(RatingDialogTestTags.STAR_PREFIX + score)
                    .selectable(
                        selected = state.score == score,
                        enabled = !state.submitting && !state.terminalError,
                        role = Role.RadioButton,
                        onClick = { onScore(score) },
                    )
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
              Text(
                  if (score <= state.score) "★" else "☆",
                  fontSize = 34.sp,
                  color =
                      RatingAmber.copy(
                          alpha = if (state.submitting || state.terminalError) 0.55f else 1f
                      ),
                  modifier = Modifier.clearAndSetSemantics {},
              )
            }
          }
        }
        Text(
            if (state.score == 0) stringResource(R.string.rating_select)
            else stringResource(R.string.rating_value, state.score, RATING_SCORE_RANGE.last),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.comment,
            onValueChange = onComment,
            enabled = !state.submitting && !state.terminalError,
            modifier = Modifier.fillMaxWidth().testTag(RatingDialogTestTags.COMMENT),
            label = { Text(stringResource(R.string.rating_comment)) },
            placeholder = { Text(stringResource(R.string.rating_comment_hint)) },
            isError = state.commentTooLong,
            minLines = 2,
            shape = RoundedCornerShape(10.dp),
            supportingText =
                if (state.commentTooLong)
                    ({
                      Text(stringResource(R.string.rating_comment_limit, MAX_RATING_COMMENT_LENGTH))
                    })
                else null,
        )
        RatingDialogFeedback(state)
        if (!state.terminalError)
            Button(
                onClick = onSubmit,
                enabled = state.valid && !state.submitting,
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(RatingDialogTestTags.SUBMIT),
            ) {
              Text(
                  stringResource(
                      if (state.error == null) R.string.rating_submit else R.string.rating_retry
                  )
              )
            }
        OutlinedButton(
            onClick = onDismiss,
            enabled = !state.submitting,
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(RatingDialogTestTags.CANCEL),
        ) {
          Text(
              stringResource(
                  if (state.terminalError) R.string.rating_close else R.string.rating_cancel
              )
          )
        }
      }
    }
  }
}

/** Emits feedback directly into the dialog column, preserving its spacing and live regions. */
@Composable
private fun RatingDialogFeedback(state: RatingDialogState) {
  if (state.submitting) {
    LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(
        stringResource(R.string.rating_submitting),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
  state.error?.let {
    val message =
        when (it) {
          RatingError.NotAuthenticated -> R.string.order_details_authentication_required
          RatingError.PermissionDenied -> R.string.order_details_permission_denied
          RatingError.AlreadyRated -> R.string.rating_already
          RatingError.Unknown -> R.string.rating_failure
          RatingError.NotFound,
          RatingError.OrderNotDelivered,
          RatingError.InvalidData -> R.string.rating_unavailable
        }
    Text(
        stringResource(message),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
}
