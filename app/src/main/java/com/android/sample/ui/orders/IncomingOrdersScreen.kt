package com.android.sample.ui.orders

// AI assistance: Claude Code.
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.ui.components.SkeletonCard
import com.android.sample.ui.components.StateCard

/** Test tags of the incoming-orders screen. */
object IncomingOrdersTestTags {
  const val LOADING = "incoming_orders_loading"
  const val EMPTY = "incoming_orders_empty"
  const val REFRESH = "incoming_orders_refresh"
  const val ERROR = "incoming_orders_error"
  const val RETRY = "incoming_orders_retry"
  const val ORDER_CARD_PREFIX = "incoming_orders_card_"
}

// Same spacing as the My Orders and My Trips lists.
private val SCREEN_PADDING = 20.dp
private val SECTION_SPACING = 14.dp
private val HEADER_START_PADDING = 4.dp
private val HEADER_TOP_PADDING = 16.dp
private val CARD_PADDING = 16.dp
private val CARD_CONTENT_SPACING = 8.dp
private val CARD_CORNER_RADIUS = 16.dp
private val CARD_BORDER_WIDTH = 1.dp
private const val LOADING_PLACEHOLDER_COUNT = 3

/**
 * Orders placed on one trip, shown to its owner. Loads them through [viewModel] and opens an order
 * with [onOrderClick], which receives the order ID.
 */
@Composable
fun IncomingOrdersScreen(
    viewModel: IncomingOrdersViewModel,
    onOrderClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  val state by viewModel.uiState.collectAsState()
  IncomingOrdersScreen(state, viewModel::refresh, onOrderClick, modifier)
}

/**
 * Stateless incoming-orders list: one card per order with its ID, status, item count and total,
 * plus the loading, empty and error states. [onRefresh] reloads the list from the empty and error
 * states; [onOrderClick] receives the ID of the selected order.
 */
@Composable
fun IncomingOrdersScreen(
    state: IncomingOrdersUiState,
    onRefresh: () -> Unit,
    onOrderClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Column(
      modifier.fillMaxSize().background(colors.background).padding(horizontal = SCREEN_PADDING),
      verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
  ) {
    Column(Modifier.padding(start = HEADER_START_PADDING, top = HEADER_TOP_PADDING)) {
      Text(
          stringResource(R.string.incoming_orders_title),
          color = colors.onBackground,
          style = MaterialTheme.typography.headlineMedium,
      )
      Text(
          stringResource(R.string.incoming_orders_subtitle),
          color = colors.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
      )
    }
    when (state) {
      IncomingOrdersUiState.Loading -> {
        val loading = stringResource(R.string.incoming_orders_loading)
        Column(
            Modifier.testTag(IncomingOrdersTestTags.LOADING).semantics {
              contentDescription = loading
            },
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
        ) {
          repeat(LOADING_PLACEHOLDER_COUNT) { SkeletonCard() }
        }
      }
      is IncomingOrdersUiState.Content ->
          LazyColumn(
              verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
              contentPadding = PaddingValues(bottom = SCREEN_PADDING),
          ) {
            items(state.rows, key = { it.order.id }) { row ->
              IncomingOrderCard(row) { onOrderClick(row.order.id) }
            }
          }
      IncomingOrdersUiState.Empty ->
          StateCard(
              tag = IncomingOrdersTestTags.EMPTY,
              icon = R.drawable.ic_trip_shopping_bag,
              message = stringResource(R.string.incoming_orders_empty),
              action = stringResource(R.string.incoming_orders_refresh),
              actionTag = IncomingOrdersTestTags.REFRESH,
              onAction = onRefresh,
          )
      is IncomingOrdersUiState.Error ->
          StateCard(
              tag = IncomingOrdersTestTags.ERROR,
              icon = R.drawable.ic_trip_alert_circle,
              message = stringResource(R.string.incoming_orders_error),
              action = stringResource(R.string.incoming_orders_retry),
              actionTag = IncomingOrdersTestTags.RETRY,
              onAction = onRefresh,
          )
    }
  }
}

/** One order: its ID, then its status, item count and total. */
@Composable
private fun IncomingOrderCard(row: IncomingOrderRow, onClick: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  // The plural only needs the category; the displayed count stays exact.
  val quantity = row.itemCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
  Card(
      onClick = onClick,
      modifier =
          Modifier.fillMaxWidth().testTag(IncomingOrdersTestTags.ORDER_CARD_PREFIX + row.order.id),
      shape = RoundedCornerShape(CARD_CORNER_RADIUS),
      colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
      border = BorderStroke(CARD_BORDER_WIDTH, colors.outlineVariant),
  ) {
    Column(
        Modifier.padding(CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(CARD_CONTENT_SPACING),
    ) {
      Text(
          stringResource(R.string.incoming_orders_order, row.order.id),
          color = colors.onSurface,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        OrderStatusBadge(row.order.status)
        Spacer(Modifier.weight(1f))
        Text(
            pluralStringResource(
                R.plurals.incoming_orders_items,
                quantity,
                row.itemCount,
                money(row.totalCents),
            ),
            color = colors.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
  }
}
