package com.android.sample.ui.notifications

// AI assistance: OpenAI Codex. Layout: Notifications proposal, Figma #190/#202.
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.sample.R
import com.android.sample.model.*
import com.android.sample.ui.orders.OrderStatusBadge
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Stable UI/navigation tags; IDs preserve the repository's opaque event identity. */
object NotificationTestTags {
  const val LIST = "notification_list"
  const val INBOX = "notification_inbox"
  const val PREFERENCES = "notification_preferences"
  const val LOADING = "notification_loading"
  const val REFRESH = "notification_refresh"
  const val RETRY = "notification_retry"
  const val SAVE_RETRY = "notification_save_retry"
  const val SETTINGS = "notification_settings"
  const val BACK = "notification_back"
  const val NEW_ORDERS = "notification_new_orders"
  const val DECISIONS = "notification_decisions"
  const val ROW = "notification_row_"
  const val READ = "notification_read_"
  const val ORDER = "notification_order_"
}

// Spacing and corner radius from the editable notification proposal.
private val PAGE_PADDING = 20.dp
private val SECTION_GAP = 12.dp
private val CARD_PADDING = 16.dp
private val CARD_RADIUS = 20.dp
private val LINE_GAP = 8.dp
private val MIN_TOUCH_HEIGHT = 48.dp
private val TITLE_SIZE = 26.sp
private const val REFERENCE_SUFFIX_LENGTH = 8

/**
 * Scrollable inbox; callers own loading and confirmed read-state updates. The authenticated host
 * must leave this destination when the session ends.
 *
 * @param onMarkRead receives the full event ID; writes are disabled while the page is saving.
 * @param onOrder receives the full order ID, including characters hidden in the displayed suffix.
 * @param onRetrySave retries the last failed read-state update.
 */
@Composable
fun NotificationInboxScreen(
    state: NotificationPageState<List<OrderNotification>>,
    onBack: () -> Unit,
    onPreferences: () -> Unit,
    onRefresh: () -> Unit,
    onMarkRead: (String) -> Unit,
    onRetrySave: () -> Unit,
    onOrder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  NotificationPage(
      R.string.notifications_title,
      onBack,
      modifier.testTag(NotificationTestTags.INBOX),
  ) {
    LazyColumn(
        Modifier.weight(1f).testTag(NotificationTestTags.LIST),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
        contentPadding = PaddingValues(bottom = PAGE_PADDING),
    ) {
      item {
        TextButton(onPreferences, Modifier.testTag(NotificationTestTags.SETTINGS)) {
          Text(stringResource(R.string.notifications_preferences))
        }
      }
      if (state is NotificationPageState.Content && state.value.isNotEmpty()) {
        item {
          val unread = state.value.count { !it.isRead }
          Text(
              pluralStringResource(R.plurals.notifications_unread, unread, unread),
              style = MaterialTheme.typography.titleSmall,
          )
          SaveFeedback(
              state.isSaving,
              state.saveError != null,
              onRetrySave,
              state.refreshError != null,
          )
        }
        items(state.value, key = { it.event.id }) { notification ->
          NotificationCard(notification, !state.isSaving, onMarkRead, onOrder)
        }
      } else
          item {
            PageState(state, onRefresh) {
              Text(stringResource(R.string.notifications_empty))
              PageActionButton(
                  onRefresh,
                  NotificationTestTags.REFRESH,
                  R.string.notifications_refresh,
              )
            }
          }
    }
  }
}

/**
 * Confirmed account preferences; a failed change keeps the saved values and offers a retry. The
 * authenticated host must leave this destination when the session ends.
 *
 * @param onChange receives the requested settings; the caller persists only changed toggles.
 * @param onRetrySave retries the failed preference update without an optimistic UI change.
 */
@Composable
fun NotificationPreferencesScreen(
    state: NotificationPageState<NotificationPreferences>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onChange: (NotificationPreferences) -> Unit,
    onRetrySave: () -> Unit,
    modifier: Modifier = Modifier,
) {
  NotificationPage(
      R.string.notifications_preferences,
      onBack,
      modifier.testTag(NotificationTestTags.PREFERENCES),
      scrollable = true,
  ) {
    PageState(state, onRefresh) { content ->
      Text(
          stringResource(R.string.notifications_choose),
          style = MaterialTheme.typography.titleMedium,
      )
      Text(stringResource(R.string.notifications_settings_description))
      Card(shape = RoundedCornerShape(CARD_RADIUS), colors = notificationCardColors()) {
        Column(Modifier.padding(CARD_PADDING)) {
          PreferenceRow(
              R.string.notifications_new_orders,
              R.string.notifications_new_orders_help,
              content.value.newOrders,
              !content.isSaving,
              NotificationTestTags.NEW_ORDERS,
          ) {
            onChange(content.value.copy(newOrders = it))
          }
          HorizontalDivider()
          PreferenceRow(
              R.string.notifications_decisions,
              R.string.notifications_decisions_help,
              content.value.orderDecisions,
              !content.isSaving,
              NotificationTestTags.DECISIONS,
          ) {
            onChange(content.value.copy(orderDecisions = it))
          }
        }
      }
      SaveFeedback(
          content.isSaving,
          content.saveError != null,
          onRetrySave,
          content.refreshError != null,
      )
      Text(
          stringResource(R.string.notifications_preferences_scope),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodyMedium,
      )
      Card(shape = RoundedCornerShape(CARD_RADIUS), colors = notificationCardColors()) {
        Text(stringResource(R.string.notifications_in_app), Modifier.padding(CARD_PADDING))
      }
    }
  }
}

@Composable
private fun NotificationPage(
    @StringRes title: Int,
    onBack: () -> Unit,
    modifier: Modifier,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
  Column(
      modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
          .padding(PAGE_PADDING),
      verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
          stringResource(title),
          Modifier.weight(1f),
          style = MaterialTheme.typography.titleMedium.copy(fontSize = TITLE_SIZE),
      )
      TextButton(onBack, Modifier.testTag(NotificationTestTags.BACK)) {
        Text(stringResource(R.string.nav_back))
      }
    }
    content()
  }
}

@Composable
private fun <T> PageState(
    state: NotificationPageState<T>,
    onRefresh: () -> Unit,
    content: @Composable (NotificationPageState.Content<T>) -> Unit,
) {
  when (state) {
    NotificationPageState.Loading -> {
      CircularProgressIndicator(Modifier.testTag(NotificationTestTags.LOADING))
      Text(stringResource(R.string.notifications_loading))
    }
    is NotificationPageState.Error -> {
      Text(
          stringResource(R.string.notifications_load_error),
          color = MaterialTheme.colorScheme.error,
      )
      PageActionButton(onRefresh)
    }
    is NotificationPageState.Content -> content(state)
    // Hide private content immediately; the authenticated host owns the redirect.
    NotificationPageState.SessionEnded -> Unit
  }
}

@Composable
private fun NotificationCard(
    notification: OrderNotification,
    enabled: Boolean,
    onMarkRead: (String) -> Unit,
    onOrder: (String) -> Unit,
) {
  val event = notification.event
  val title =
      when (event.type) {
        NotificationEventType.ORDER_PLACED -> R.string.notifications_placed
        NotificationEventType.ORDER_ACCEPTED -> R.string.notifications_accepted
        NotificationEventType.ORDER_REJECTED -> R.string.notifications_rejected
      }
  val message =
      when (event.type) {
        NotificationEventType.ORDER_PLACED -> R.string.notifications_placed_body
        NotificationEventType.ORDER_ACCEPTED -> R.string.notifications_accepted_body
        NotificationEventType.ORDER_REJECTED -> R.string.notifications_rejected_body
      }
  val locale = LocalConfiguration.current.locales[0]
  val zone = ZoneId.systemDefault()
  val date =
      remember(event.occurredAt, locale, zone) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone)
            .format(event.occurredAt)
      }
  Card(
      Modifier.fillMaxWidth().testTag(NotificationTestTags.ROW + event.id),
      shape = RoundedCornerShape(CARD_RADIUS),
      colors = notificationCardColors(),
  ) {
    Column(Modifier.padding(CARD_PADDING), verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
      Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(LINE_GAP),
      ) {
        Text(
            stringResource(title),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(
                if (notification.isRead) R.string.notifications_read
                else R.string.notifications_unread
            ),
            color =
                if (notification.isRead) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
      }
      OrderStatusBadge(
          when (event.type) {
            NotificationEventType.ORDER_PLACED -> OrderStatus.PENDING
            NotificationEventType.ORDER_ACCEPTED -> OrderStatus.ACCEPTED
            NotificationEventType.ORDER_REJECTED -> OrderStatus.REJECTED
          }
      )
      Text(
          date,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(stringResource(message))
      Text(
          stringResource(
              R.string.notifications_order_reference,
              if (event.orderId.length > REFERENCE_SUFFIX_LENGTH)
                  "…" + event.orderId.takeLast(REFERENCE_SUFFIX_LENGTH)
              else event.orderId,
          ),
          style = MaterialTheme.typography.bodySmall,
      )
      // A wrapping flow keeps both actions reachable with large fonts and narrow displays.
      FlowRow(horizontalArrangement = Arrangement.spacedBy(LINE_GAP)) {
        TextButton(
            { onOrder(event.orderId) },
            Modifier.testTag(NotificationTestTags.ORDER + event.id),
        ) {
          Text(stringResource(R.string.notifications_view_order))
        }
        if (!notification.isRead)
            TextButton(
                { onMarkRead(event.id) },
                Modifier.testTag(NotificationTestTags.READ + event.id),
                enabled = enabled,
            ) {
              Text(stringResource(R.string.notifications_mark_read))
            }
      }
    }
  }
}

@Composable
private fun PreferenceRow(
    @StringRes title: Int,
    @StringRes help: Int,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
  Row(
      Modifier.fillMaxWidth()
          .heightIn(min = MIN_TOUCH_HEIGHT)
          .testTag(tag)
          .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
          .padding(vertical = SECTION_GAP),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(LINE_GAP),
  ) {
    Column(Modifier.weight(1f)) {
      Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
      Text(
          stringResource(help),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked, onCheckedChange = null, enabled = enabled)
  }
}

@Composable
private fun SaveFeedback(
    saving: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    refreshFailed: Boolean,
) {
  if (saving || failed || refreshFailed) {
    Text(
        stringResource(
            when {
              saving -> R.string.notifications_saving
              failed -> R.string.notifications_save_error
              else -> R.string.notifications_refresh_error
            }
        ),
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color =
            if (failed || refreshFailed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (failed || refreshFailed) PageActionButton(onRetry, NotificationTestTags.SAVE_RETRY)
  }
}

@Composable
private fun PageActionButton(
    onAction: () -> Unit,
    tag: String = NotificationTestTags.RETRY,
    @StringRes label: Int = R.string.notifications_retry,
) {
  TextButton(onAction, Modifier.testTag(tag)) { Text(stringResource(label)) }
}

@Composable
private fun notificationCardColors() =
    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
