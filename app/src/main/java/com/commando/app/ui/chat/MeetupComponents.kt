package com.commando.app.ui.chat

// AI assistance: OpenAI Codex. Reuses the existing trip date/time pickers and chat styling.
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.android.sample.R
import com.commando.app.model.*
import com.commando.app.ui.trips.TripDatePickerDialog
import com.commando.app.ui.trips.TripTimePickerDialog
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Selects a future local date/time and place; the repository and rules revalidate on commit. */
@Composable
internal fun MeetupComposer(state: OrderChatUiState, onPropose: (String, Instant) -> Unit) {
  var open by rememberSaveable { mutableStateOf(false) }
  var location by rememberSaveable { mutableStateOf("") }
  var chosen by rememberSaveable { mutableStateOf<Long?>(null) }
  var datePicker by remember { mutableStateOf(false) }
  var timePicker by remember { mutableStateOf(false) }
  val context = state.context ?: return
  if (
      context.viewerId != context.trip.ownerId ||
          !context.trip.status.canReportDelay ||
          state.chat?.status?.canSendChat != true
  )
      return
  val zone = ZoneId.systemDefault()
  val time = chosen?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: ZonedDateTime.now(zone)
  TextButton(
      onClick = {
        open = true
        location = context.trip.handoffLocation.name
        chosen = null
      },
      enabled = state.canSendDelay,
      modifier = Modifier.testTag(OrderChatTestTags.MEETUP),
  ) {
    Text(stringResource(R.string.chat_meetup_propose))
  }
  if (open)
      AlertDialog(
          onDismissRequest = { open = false },
          title = { Text(stringResource(R.string.chat_meetup_propose)) },
          text = {
            Column {
              OutlinedTextField(
                  location,
                  { location = it },
                  label = { Text(stringResource(R.string.chat_meetup_location)) },
                  isError = !location.isValidChatText(),
                  singleLine = true,
                  modifier = Modifier.testTag(OrderChatTestTags.MEETUP_LOCATION),
              )
              TextButton(onClick = { datePicker = true }) {
                Text(stringResource(R.string.create_trip_date_label))
              }
              TextButton(onClick = { timePicker = true }) {
                Text(stringResource(R.string.chat_meetup_time))
              }
              chosen?.let { Text(meetupTime(Instant.ofEpochMilli(it))) }
              Text(stringResource(R.string.chat_meetup_validation))
            }
          },
          confirmButton = {
            TextButton(
                onClick = {
                  chosen?.let { onPropose(location, Instant.ofEpochMilli(it)) }
                  open = false
                },
                enabled =
                    state.canSendDelay &&
                        location.isValidChatText() &&
                        chosen?.let { Instant.ofEpochMilli(it).isAfter(Instant.now()) } == true,
                modifier = Modifier.testTag(OrderChatTestTags.MEETUP_SEND),
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
  if (open && datePicker)
      TripDatePickerDialog(time.toLocalDate(), LocalDate.now(zone), { datePicker = false }) {
        chosen = it.atTime(time.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
        datePicker = false
      }
  if (open && timePicker)
      TripTimePickerDialog(time.toLocalTime(), { timePicker = false }) {
        chosen = time.toLocalDate().atTime(it).atZone(zone).toInstant().toEpochMilli()
        timePicker = false
      }
}

/** Both participants keep the proposed details and the one final response in the same message. */
@Composable
internal fun MeetupCard(
    message: ChatMessage,
    state: OrderChatUiState,
    onRespond: (String, MeetupResponse) -> Unit,
) {
  val proposal = requireNotNull(message.meetup)
  Text(stringResource(R.string.chat_meetup_title), style = MaterialTheme.typography.labelMedium)
  Text(proposal.location)
  Text(meetupTime(proposal.at))
  Text(
      stringResource(
          when (proposal.response) {
            MeetupResponse.PENDING ->
                if (proposal.at.isAfter(Instant.now())) R.string.chat_meetup_pending
                else R.string.chat_meetup_expired
            MeetupResponse.ACCEPTED -> R.string.chat_meetup_accepted
            MeetupResponse.DECLINED -> R.string.chat_meetup_declined
          }
      ),
      Modifier.testTag(OrderChatTestTags.MEETUP_STATE + message.id),
  )
  if (
      proposal.response == MeetupResponse.PENDING &&
          proposal.at.isAfter(Instant.now()) &&
          state.context?.let {
            it.viewerId == it.order.requesterId && it.viewerId != message.senderId
          } == true &&
          state.chat?.status?.canSendChat == true
  ) {
    Row {
      for (response in listOf(MeetupResponse.ACCEPTED, MeetupResponse.DECLINED)) {
        TextButton(
            onClick = { onRespond(message.id, response) },
            enabled = state.canEditDraft,
            modifier =
                Modifier.testTag(OrderChatTestTags.MEETUP_RESPONSE + message.id + response.name),
        ) {
          Text(
              stringResource(
                  if (response == MeetupResponse.ACCEPTED) R.string.order_decision_accept
                  else R.string.order_decision_reject
              )
          )
        }
      }
    }
  }
}

@Composable
private fun meetupTime(at: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(LocalConfiguration.current.locales[0])
        .withZone(ZoneId.systemDefault())
        .format(at)

@Composable
internal fun MeetupFeedback(state: MeetupActionState, retry: () -> Unit) {
  if (state.busy) Text(stringResource(R.string.chat_meetup_sending))
  if (state.error != null) {
    Text(stringResource(R.string.chat_meetup_failure))
    TextButton(onClick = retry, modifier = Modifier.testTag(OrderChatTestTags.MEETUP_RETRY)) {
      Text(stringResource(R.string.my_orders_retry))
    }
  }
}
