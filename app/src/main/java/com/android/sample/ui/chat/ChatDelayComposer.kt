package com.android.sample.ui.chat

// AI assistance: OpenAI Codex.
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.android.sample.R
import com.commando.app.model.MAX_CHAT_DELAY_MINUTES
import com.commando.app.model.canSendChat

/** Owner-only delay input; the ViewModel and persisted rules also enforce role and range. */
@Composable
internal fun ChatDelayComposer(state: OrderChatUiState, onSend: (Int) -> Unit) {
  var open by rememberSaveable { mutableStateOf(false) }
  var input by rememberSaveable { mutableStateOf("") }
  if (state.context?.let { it.viewerId == it.trip.ownerId } != true) return
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
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.chat_delay_action)) },
        text = {
          Column {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.testTag(OrderChatTestTags.DELAY_INPUT),
                label = { Text(stringResource(R.string.chat_delay_minutes)) },
                supportingText = {
                  Text(stringResource(R.string.chat_delay_range, MAX_CHAT_DELAY_MINUTES))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = input.isNotEmpty() && minutes == null,
            )
          }
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
