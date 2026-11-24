package com.commando.app.ui.chat

// AI assistance: OpenAI Codex.
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.android.sample.R
import com.commando.app.model.MAX_CHAT_DELAY_MINUTES
import com.commando.app.model.canReportDelay
import com.commando.app.model.canSendChat

/** Owner-only delay input; the ViewModel and persisted rules also enforce role and range. */
@Composable
internal fun ChatDelayComposer(state: OrderChatUiState, onSend: (Int) -> Unit) {
  var open by rememberSaveable { mutableStateOf(false) }
  var input by rememberSaveable { mutableStateOf("") }
  if (
      state.context?.let { it.viewerId == it.trip.ownerId && it.trip.status.canReportDelay } != true
  )
      return
  if (state.chat?.status?.canSendChat != true) return
  TextButton(
      onClick = {
        input = ""
        open = true
      },
      modifier = Modifier.testTag(OrderChatTestTags.DELAY),
      enabled = state.canSendDelay,
  ) {
    Text(stringResource(R.string.chat_delay_action))
  }
  if (open) {
    val minutes = input.toIntOrNull()?.takeIf { it in 1..MAX_CHAT_DELAY_MINUTES }
    val invalid = input.isNotEmpty() && minutes == null
    val hint =
        stringResource(
            if (invalid) R.string.chat_delay_invalid else R.string.chat_delay_range,
            MAX_CHAT_DELAY_MINUTES,
        )
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.chat_delay_action)) },
        text = {
          OutlinedTextField(
              value = input,
              onValueChange = { input = it },
              modifier =
                  Modifier.testTag(OrderChatTestTags.DELAY_INPUT).semantics {
                    if (invalid) error(hint)
                  },
              label = { Text(stringResource(R.string.chat_delay_minutes)) },
              supportingText = { Text(hint) },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              isError = invalid,
          )
        },
        confirmButton = {
          TextButton(
              onClick = {
                minutes?.let(onSend)
                open = false
              },
              modifier = Modifier.testTag(OrderChatTestTags.DELAY_SEND),
              enabled = state.canSendDelay && minutes != null,
          ) {
            Text(stringResource(R.string.chat_send))
          }
        },
        dismissButton = {
          TextButton(onClick = { open = false }) {
            Text(stringResource(R.string.profile_edit_cancel))
          }
        },
    )
  }
}
