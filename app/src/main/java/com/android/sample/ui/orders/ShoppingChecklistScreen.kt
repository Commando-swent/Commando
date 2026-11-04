package com.android.sample.ui.orders

// AI assistance: Claude Code.
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.model.ShoppingChecklistItem
import com.android.sample.ui.components.SkeletonCard
import com.android.sample.ui.components.StateCard

/** Test tags of the shopping checklist screen. */
object ShoppingChecklistTestTags {
  const val LOADING = "shopping_checklist_loading"
  const val EMPTY = "shopping_checklist_empty"
  const val REFRESH = "shopping_checklist_refresh"
  const val ERROR = "shopping_checklist_error"
  const val RETRY = "shopping_checklist_retry"
  const val PROGRESS = "shopping_checklist_progress"
  const val SAVE_ERROR = "shopping_checklist_save_error"
  const val ITEM_PREFIX = "shopping_checklist_item_"
}

// Same spacing as the Incoming Orders list.
private val SCREEN_PADDING = 20.dp
private val SECTION_SPACING = 14.dp
private val HEADER_START_PADDING = 4.dp
private val HEADER_TOP_PADDING = 16.dp
private val ITEM_PADDING = 12.dp
private const val LOADING_PLACEHOLDER_COUNT = 3

/** Shopping checklist of one trip, loaded and saved through [viewModel]. */
@Composable
fun ShoppingChecklistScreen(viewModel: ShoppingChecklistViewModel, modifier: Modifier = Modifier) {
  val state by viewModel.uiState.collectAsState()
  ShoppingChecklistScreen(state, viewModel::refresh, viewModel::setPurchased, modifier)
}

/**
 * Stateless shopping checklist: purchase progress and one checkable row per product, plus the
 * loading, empty and error states. [onRefresh] reloads from the empty and error states;
 * [onPurchasedChange] receives the toggled item and its new purchased value.
 */
@Composable
fun ShoppingChecklistScreen(
    state: ShoppingChecklistUiState,
    onRefresh: () -> Unit,
    onPurchasedChange: (ShoppingChecklistItem, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Column(
      modifier.fillMaxSize().background(colors.background).padding(horizontal = SCREEN_PADDING),
      verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
  ) {
    Text(
        stringResource(R.string.shopping_checklist_title),
        modifier = Modifier.padding(start = HEADER_START_PADDING, top = HEADER_TOP_PADDING),
        color = colors.onBackground,
        style = MaterialTheme.typography.headlineMedium,
    )
    when (state) {
      ShoppingChecklistUiState.Loading -> {
        val loading = stringResource(R.string.shopping_checklist_loading)
        Column(
            Modifier.testTag(ShoppingChecklistTestTags.LOADING).semantics {
              contentDescription = loading
            },
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
        ) {
          repeat(LOADING_PLACEHOLDER_COUNT) { SkeletonCard() }
        }
      }
      is ShoppingChecklistUiState.Content -> ChecklistContent(state, onPurchasedChange)
      ShoppingChecklistUiState.Empty ->
          StateCard(
              tag = ShoppingChecklistTestTags.EMPTY,
              icon = R.drawable.ic_trip_shopping_bag,
              message = stringResource(R.string.shopping_checklist_empty),
              action = stringResource(R.string.shopping_checklist_refresh),
              actionTag = ShoppingChecklistTestTags.REFRESH,
              onAction = onRefresh,
          )
      is ShoppingChecklistUiState.Error ->
          StateCard(
              tag = ShoppingChecklistTestTags.ERROR,
              icon = R.drawable.ic_trip_alert_circle,
              message = stringResource(R.string.shopping_checklist_error),
              action = stringResource(R.string.shopping_checklist_retry),
              actionTag = ShoppingChecklistTestTags.RETRY,
              onAction = onRefresh,
          )
    }
  }
}

/** Progress, the optional save error, then one row per product. */
@Composable
private fun ChecklistContent(
    state: ShoppingChecklistUiState.Content,
    onPurchasedChange: (ShoppingChecklistItem, Boolean) -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val purchased = state.items.count { it.purchased }
  Text(
      // The plural follows the purchased count, e.g. "1 sur 2 acheté" in French.
      pluralStringResource(
          R.plurals.shopping_checklist_progress,
          purchased,
          purchased,
          state.items.size,
      ),
      modifier = Modifier.testTag(ShoppingChecklistTestTags.PROGRESS),
      color = colors.onBackground,
      style = MaterialTheme.typography.titleMedium,
  )
  if (state.saveError != null) {
    Text(
        stringResource(R.string.shopping_checklist_save_error),
        modifier = Modifier.testTag(ShoppingChecklistTestTags.SAVE_ERROR),
        color = colors.error,
        style = MaterialTheme.typography.bodyMedium,
    )
  }
  LazyColumn(contentPadding = PaddingValues(bottom = SCREEN_PADDING)) {
    items(state.items, key = { it.productId }) { item ->
      ChecklistRow(item) { purchased -> onPurchasedChange(item, purchased) }
    }
  }
}

/** One product: a checkbox, its name and the quantity to buy; the whole row toggles it. */
@Composable
private fun ChecklistRow(item: ShoppingChecklistItem, onPurchasedChange: (Boolean) -> Unit) {
  val colors = MaterialTheme.colorScheme
  Row(
      Modifier.fillMaxWidth()
          .testTag(ShoppingChecklistTestTags.ITEM_PREFIX + item.productId)
          .toggleable(
              value = item.purchased,
              role = Role.Checkbox,
              onValueChange = onPurchasedChange,
          )
          .padding(ITEM_PADDING),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // The row handles clicks, so the checkbox only shows the state.
    Checkbox(checked = item.purchased, onCheckedChange = null)
    Text(
        item.name,
        modifier = Modifier.weight(1f).padding(horizontal = ITEM_PADDING),
        color = colors.onSurface,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        stringResource(R.string.shopping_checklist_quantity, item.requiredQuantity),
        color = colors.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
  }
}
