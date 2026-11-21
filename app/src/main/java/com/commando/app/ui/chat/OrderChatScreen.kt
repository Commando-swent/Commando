package com.commando.app.ui.chat

// AI assistance: OpenAI Codex. Basic text-chat layout from the Sprint 6 Figma frames.
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.sample.R
import com.commando.app.data.repository.OrderError
import com.commando.app.model.*
import com.commando.app.ui.orders.OrderStatusBadge
import com.commando.app.ui.orders.orderStatusLabel
import com.commando.app.ui.theme.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Stable selectors independent of text, locale and ordering of controls. */
object OrderChatTestTags {
  const val SCREEN = "order_chat"
  const val BACK = "chat_back"
  const val INFO = "chat_info"
  const val INPUT = "chat_input"
  const val SEND = "chat_send"
  const val RETRY = "chat_retry"
  const val RETRY_SEND = "chat_retry_send"
  const val READ_ONLY = "chat_read_only"
  const val EMPTY = "chat_empty"
  const val LOADING = "chat_loading"
  const val ERROR = "chat_error"
  const val HISTORY = "chat_history"
  const val MESSAGE = "chat_message_"
}

/** Starts collection only while resumed, cancelling it on navigation, backgrounding or disposal. */
@Composable
internal fun OrderChatHost(model: OrderChatViewModel, onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(model, lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> model.start()
        Lifecycle.Event.ON_PAUSE -> model.stop()
        else -> Unit
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      model.stop()
    }
  }
  val state by model.uiState.collectAsState()
  OrderChatScreen(state, onBack, model::start, model::updateDraft, model::send)
}

/** Basic text conversation; callbacks own persistence and navigation, never this renderer. */
@Composable
fun OrderChatScreen(
    state: OrderChatUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Column(
      modifier
          .fillMaxSize()
          .testTag(OrderChatTestTags.SCREEN)
          .background(
              Brush.verticalGradient(
                  0f to ChatGradientTop,
                  0.4f to ChatGradientMiddle,
                  1f to colors.surfaceContainer,
              )
          )
          .safeDrawingPadding()
          .imePadding()
  ) {
    ChatHeader(state.context, onBack)
    if (state.context != null) ChatOrderContext(state.context, onBack)
    if (state.loading) {
      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.testTag(OrderChatTestTags.LOADING))
      }
    } else {
      when (val error = state.error) {
        null ->
            when (state.chat) {
              null -> Spacer(Modifier.weight(1f))
              else -> {
                Box(Modifier.weight(1f).fillMaxWidth()) { ChatHistory(state) }
                ChatComposer(state, onDraft, onSend)
              }
            }
        else ->
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
              Column(
                  Modifier.padding(24.dp).testTag(OrderChatTestTags.ERROR),
                  horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Text(
                    stringResource(chatLoadErrorMessage(error)),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (error != OrderError.NotAuthenticated)
                    TextButton(onRetry, Modifier.testTag(OrderChatTestTags.RETRY)) {
                      Text(stringResource(R.string.my_orders_retry))
                    }
              }
            }
      }
    }
  }
}

/** Maps a known load failure to its localized message without inferring nullable UI state. */
@androidx.annotation.StringRes
private fun chatLoadErrorMessage(error: OrderError): Int =
    when (error) {
      OrderError.NotAuthenticated -> R.string.order_details_authentication_required
      OrderError.PermissionDenied -> R.string.order_details_permission_denied
      OrderError.NotFound -> R.string.order_details_not_found
      else -> R.string.chat_load_error
    }

@Composable
private fun ChatHeader(context: ChatContext?, onBack: () -> Unit) {
  val role = partnerRole(context)
  Row(
      Modifier.fillMaxWidth().padding(20.dp, 16.dp, 20.dp, 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    IconButton(
        onBack,
        Modifier.size(48.dp).background(ChatSurface, CircleShape).testTag(OrderChatTestTags.BACK),
    ) {
      Icon(painterResource(R.drawable.ic_trip_arrow_left), stringResource(R.string.nav_back))
    }
    Surface(
        shape = CircleShape,
        color = ChatAvatarContainer,
        contentColor = ChatAvatarText,
        border = BorderStroke(1.dp, ChatAvatarOutline),
    ) {
      Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        Text(role.take(1), style = MaterialTheme.typography.titleMedium)
      }
    }
    Column(Modifier.weight(1f)) {
      Text(
          role,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.semantics { heading() },
      )
      if (context != null)
          Text(
              stringResource(R.string.notifications_order_reference, context.order.id),
              style = MaterialTheme.typography.bodySmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
    }
    IconButton(
        onBack,
        Modifier.size(48.dp).background(ChatSurface, CircleShape).testTag(OrderChatTestTags.INFO),
    ) {
      Icon(
          painterResource(R.drawable.chat_info),
          stringResource(R.string.order_details_title),
          Modifier.size(23.dp),
      )
    }
  }
}

@Composable
private fun partnerRole(context: ChatContext?): String =
    stringResource(
        when (context?.partnerIsCommando) {
          true -> R.string.home_commando
          false -> R.string.home_requester
          null -> R.string.chat_title
        }
    )

@Composable
private fun ChatOrderContext(context: ChatContext, onInfo: () -> Unit) {
  Surface(
      onClick = onInfo,
      Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      color = ChatSurface,
      border = BorderStroke(1.dp, ChatOutline),
  ) {
    Row(
        Modifier.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(painterResource(R.drawable.ic_trip_shopping_bag), null, Modifier.size(22.dp))
      Column(Modifier.weight(1f)) {
        val count = context.order.itemCount
        Text(
            stringResource(
                R.string.chat_context,
                context.trip.store.name,
                pluralStringResource(
                    R.plurals.my_orders_items,
                    count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    count,
                ),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.order_handoff, context.trip.handoffLocation.name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Icon(painterResource(R.drawable.chat_chevron), null, Modifier.size(23.dp))
    }
  }
}

@Composable
private fun ChatHistory(state: OrderChatUiState) {
  val chat = requireNotNull(state.chat)
  val locale = LocalConfiguration.current.locales[0]
  val zone = ZoneId.systemDefault()
  val dayFormat =
      remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
  val timeFormat =
      remember(locale, zone) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
      }
  val messages = chat.messages.asReversed()
  val listState = rememberLazyListState()
  LaunchedEffect(messages.firstOrNull()?.id) {
    if (listState.firstVisibleItemIndex == 0) listState.animateScrollToItem(0)
  }
  if (messages.isEmpty()) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag(OrderChatTestTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
      OrderStatusBadge(chat.status)
      Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(Modifier.size(82.dp), contentAlignment = Alignment.Center) {
          Icon(
              painterResource(R.drawable.chat_message),
              null,
              Modifier.size(37.dp),
              tint = MaterialTheme.colorScheme.primary,
          )
        }
      }
      Text(stringResource(R.string.chat_empty_title), style = MaterialTheme.typography.titleMedium)
      Text(stringResource(R.string.chat_empty_body))
    }
  } else
      LazyColumn(
          Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag(OrderChatTestTags.HISTORY),
          state = listState,
          reverseLayout = true,
          contentPadding = PaddingValues(vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(9.dp, Alignment.Bottom),
      ) {
        itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
          val date = message.createdAt.atZone(zone).toLocalDate()
          Column {
            if (
                index == messages.lastIndex ||
                    messages[index + 1].createdAt.atZone(zone).toLocalDate() != date
            )
                Text(
                    dayFormat.format(date),
                    Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            ChatBubble(
                message,
                state.context?.viewerId == message.senderId,
                timeFormat.format(message.createdAt),
            )
          }
        }
      }
}

@Composable
private fun ChatBubble(message: ChatMessage, mine: Boolean, time: String) {
  val colors = MaterialTheme.colorScheme
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    Surface(
        Modifier.align(if (mine) Alignment.CenterEnd else Alignment.CenterStart)
            .widthIn(max = maxWidth * 0.86f)
            .testTag(OrderChatTestTags.MESSAGE + message.id),
        shape =
            RoundedCornerShape(19.dp, 19.dp, if (mine) 4.dp else 19.dp, if (mine) 19.dp else 4.dp),
        color = if (mine) colors.primaryContainer else ChatSurface,
        contentColor = if (mine) colors.onPrimaryContainer else colors.onSurface,
        border = if (mine) null else BorderStroke(1.dp, ChatOutline),
    ) {
      Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(message.text)
        Text(time, Modifier.align(Alignment.End), style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

@Composable
private fun ChatComposer(state: OrderChatUiState, onDraft: (String) -> Unit, onSend: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  val sendDescription = stringResource(R.string.chat_send)
  Surface(color = colors.surfaceContainerLow) {
    Column(Modifier.fillMaxWidth()) {
      HorizontalDivider(color = colors.outlineVariant)
      if (state.chat?.status?.canSendChat != true)
          Row(
              Modifier.fillMaxWidth().padding(20.dp).testTag(OrderChatTestTags.READ_ONLY),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(painterResource(R.drawable.chat_lock), null, Modifier.size(18.dp))
            Text(
                stringResource(
                    R.string.chat_read_only,
                    stringResource(orderStatusLabel(requireNotNull(state.chat).status)),
                ),
                Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
          }
      if (state.sendError != null)
          TextButton(
              onSend,
              Modifier.align(Alignment.End).testTag(OrderChatTestTags.RETRY_SEND),
              enabled = !state.sending,
          ) {
            Text(stringResource(R.string.chat_send_failed), color = colors.error)
          }
      if (state.chat?.status?.canSendChat == true) {
        Row(
            Modifier.padding(16.dp, 12.dp, 16.dp, 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
          OutlinedTextField(
              state.draft,
              onDraft,
              Modifier.weight(1f).testTag(OrderChatTestTags.INPUT),
              enabled = state.canEditDraft,
              shape = RoundedCornerShape(25.dp),
              placeholder = { Text(stringResource(R.string.chat_placeholder)) },
              maxLines = 4,
              isError = state.draft.length > MAX_CHAT_TEXT_LENGTH,
              colors =
                  OutlinedTextFieldDefaults.colors(
                      unfocusedContainerColor = ChatSurface,
                      focusedContainerColor = ChatSurface,
                  ),
          )
          FilledIconButton(
              onSend,
              Modifier.size(50.dp).testTag(OrderChatTestTags.SEND).semantics {
                contentDescription = sendDescription
              },
              enabled = state.canSend && state.canEditDraft,
          ) {
            if (state.sending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else
                Icon(
                    painterResource(R.drawable.chat_send),
                    null,
                    Modifier.size(23.dp),
                )
          }
        }
        if (state.draft.length > MAX_CHAT_TEXT_LENGTH)
            Text(
                pluralStringResource(
                    R.plurals.chat_text_limit,
                    MAX_CHAT_TEXT_LENGTH,
                    MAX_CHAT_TEXT_LENGTH,
                ),
                Modifier.padding(horizontal = 16.dp).semantics {
                  liveRegion = LiveRegionMode.Polite
                },
                color = colors.error,
                style = MaterialTheme.typography.bodySmall,
            )
      }
    }
  }
}

/** Order Details entry uses only the selected persisted order, with no fabricated unread count. */
@Composable
internal fun OrderChatEntry(
    state: com.commando.app.ui.orders.OrderDetailsUiState.Content,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val role =
      stringResource(
          if (state.viewerId == state.order.requesterId) R.string.home_commando
          else R.string.home_requester
      )
  Surface(
      modifier = modifier,
      shape = RoundedCornerShape(19.dp),
      color = ChatSurface,
      border = BorderStroke(1.dp, ChatOutline),
  ) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(role, style = MaterialTheme.typography.titleMedium)
      Button(
          onOpen,
          Modifier.fillMaxWidth().testTag(com.commando.app.ui.orders.OrderDetailsTestTags.CHAT),
          enabled = state.canOpenChat && !state.isSaving,
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              ),
      ) {
        Icon(painterResource(R.drawable.chat_message), null, Modifier.size(21.dp))
        Text(stringResource(R.string.chat_message_partner, role), Modifier.padding(start = 8.dp))
      }
      if (!state.canOpenChat)
          Text(
              stringResource(R.string.chat_locked_pending),
              style = MaterialTheme.typography.bodySmall,
          )
    }
  }
}
