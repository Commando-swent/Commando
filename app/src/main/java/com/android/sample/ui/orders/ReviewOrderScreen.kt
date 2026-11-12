package com.android.sample.ui.orders

// AI assistance: OpenAI Codex extracted focused review sections without changing the order flow.
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.sample.R
import com.android.sample.data.repository.OrderError
import com.android.sample.model.MAX_ORDER_PRODUCTS
import com.android.sample.model.OrderItem
import com.android.sample.model.Trip

object ReviewOrderTags {
  const val COMMISSION = "order_commission"
  const val SUBMIT = "order_submit"
  const val ERROR = "order_error"
  const val TOTAL = "order_total"
}

/** Connects the shared review ViewModel to its trip and navigation callbacks. */
@Composable
fun ReviewOrderRoute(
    viewModel: ReviewOrderViewModel,
    trip: Trip,
    onBack: () -> Unit,
    onAddProducts: () -> Unit,
    onViewOrders: (String) -> Unit,
    tripSubtitle: String? = null,
    isDemo: Boolean = false,
) {
  val observed by viewModel.uiState.collectAsState()
  val state = observed.copy(tripMatches = observed.tripMatches && trip.id == observed.draft.tripId)
  val c = MaterialTheme.colorScheme
  BackHandler { if (!state.saving) onBack() }
  Column(
      Modifier.fillMaxSize()
          .background(c.background)
          .statusBarsPadding()
          .navigationBarsPadding()
          .imePadding()
  ) {
    ReviewHeader(state, onBack)
    Column(
        Modifier.weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ReviewError(state)
      if (trip.id == state.draft.tripId) ReviewTrip(trip, tripSubtitle)
      ReviewItems(state, viewModel::changeQuantity, viewModel::updateCommission, onAddProducts)
      if (state.draft.items.isNotEmpty()) ReviewTotals(state)
    }
    ReviewFooter(state, isDemo, onAddProducts, onViewOrders, viewModel::submit)
  }
}

/** Keeps the title and Back action consistent with saving and successful submission. */
@Composable
private fun ReviewHeader(state: ReviewOrderState, onBack: () -> Unit) {
  val c = MaterialTheme.colorScheme
  Row(
      Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    IconButton(
        onBack,
        enabled = !state.saving,
        modifier = Modifier.size(40.dp).background(c.surfaceContainer, CircleShape),
    ) {
      OrderIcon(R.drawable.order_back, 24, stringResource(R.string.nav_back))
    }
    Label(if (state.order != null) R.string.order_confirmation else R.string.order_review, 20, true)
  }
}

/** Displays validation or repository feedback, preserving the product-limit message priority. */
@Composable
private fun ReviewError(state: ReviewOrderState) {
  if (
      state.order != null || (state.error == null && state.tripMatches && !state.hasTooManyProducts)
  )
      return
  Text(
      if (state.tripMatches && state.hasTooManyProducts)
          stringResource(R.string.order_product_limit, MAX_ORDER_PRODUCTS)
      else stringResource(state.errorMessage()),
      Modifier.testTag(ReviewOrderTags.ERROR),
      color = MaterialTheme.colorScheme.error,
  )
}

/** Maps review failures to the existing localized creation and editing messages. */
@StringRes
private fun ReviewOrderState.errorMessage(): Int =
    when {
      !tripMatches -> R.string.order_invalid_error
      error == OrderError.NotAuthenticated -> R.string.order_session_error
      error is OrderError.AlreadyDecided -> R.string.order_edit_conflict
      editing && (error == OrderError.InvalidData || error == OrderError.PermissionDenied) ->
          R.string.order_edit_unavailable
      else -> R.string.order_failure_help
    }

/** Shows the selected trip using its existing subtitle and handoff presentation. */
@Composable
private fun ReviewTrip(trip: Trip, subtitle: String?) {
  val c = MaterialTheme.colorScheme
  Panel(c.primaryContainer) {
    Label(trip.store.name, 16, true, c.onPrimaryContainer)
    Label(subtitle ?: trip.scheduledAt.toString(), 12, color = c.onPrimaryContainer)
    Label(
        stringResource(R.string.order_handoff, trip.handoffLocation.name),
        12,
        color = c.onPrimaryContainer,
    )
  }
}

/** Shows confirmation, an empty basket, or the editable items and commission. */
@Composable
private fun ReviewItems(
    state: ReviewOrderState,
    onQuantity: (String, Int) -> Unit,
    onCommission: (String) -> Unit,
    onAddProducts: () -> Unit,
) {
  val success = state.order != null
  val c = MaterialTheme.colorScheme
  if (state.draft.items.isEmpty() || success) {
    Panel(c.primaryContainer) {
      OrderIcon(
          if (success) R.drawable.order_check else R.drawable.order_bag,
          if (success) 36 else 32,
      )
      Label(
          when {
            !success -> R.string.order_empty
            state.editing -> R.string.order_updated
            else -> R.string.order_sent
          },
          24,
          true,
      )
    }
  } else {
    Label(R.string.order_items, 11, color = c.onSurfaceVariant)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      state.draft.items.forEach { item ->
        key(item.productId) { ProductRow(item, state.editable, onQuantity) }
      }
    }
    CommissionField(state, onCommission)
    if (state.editing)
        TextButton(onClick = onAddProducts, enabled = state.editable) {
          Text(stringResource(R.string.order_add))
        }
  }
}

/** Reuses the checked totals and warns when the full amount cannot be represented. */
@Composable
private fun ReviewTotals(state: ReviewOrderState) {
  val c = MaterialTheme.colorScheme
  Panel(c.surfaceContainerLow) {
    TotalRow(R.string.order_products_total, state.productsCents)
    TotalRow(R.string.order_fee, state.commissionCents)
    TotalRow(R.string.order_total, state.totalCents, true)
  }
  if (state.order == null && state.commissionCents != null && state.totalCents == null)
      Label(R.string.order_total_invalid, 11, color = c.error)
  Label(R.string.order_estimate, 11, color = c.onSurfaceVariant)
}

/** Resolves the primary action label, giving persisted conflict recovery priority. */
@StringRes
private fun ReviewOrderState.actionTitle(): Int =
    when {
      error is OrderError.AlreadyDecided -> R.string.order_view_saved
      order != null && editing -> R.string.order_view_saved
      order != null -> R.string.order_view
      draft.items.isEmpty() -> R.string.order_add
      saving -> R.string.order_saving
      error != null -> R.string.order_retry
      editing -> R.string.order_save_changes
      else -> R.string.order_submit
    }

/** Submits the draft or opens the persisted order, keeping the existing action enablement. */
@Composable
private fun ReviewFooter(
    state: ReviewOrderState,
    isDemo: Boolean,
    onAddProducts: () -> Unit,
    onViewOrders: (String) -> Unit,
    onSubmit: () -> Unit,
) {
  val c = MaterialTheme.colorScheme
  val order = state.order
  val error = state.error
  val empty = state.draft.items.isEmpty()
  val enabled =
      order != null ||
          error is OrderError.AlreadyDecided ||
          (state.editable && (empty || state.canSubmit))
  Column(
      Modifier.fillMaxWidth()
          .background(c.surfaceContainerLowest)
          .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Button(
        onClick = {
          when {
            error is OrderError.AlreadyDecided -> onViewOrders(error.persistedOrder.id)
            order != null -> onViewOrders(order.id)
            empty -> onAddProducts()
            else -> onSubmit()
          }
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp).testTag(ReviewOrderTags.SUBMIT),
        shape = RoundedCornerShape(28.dp),
        colors =
            ButtonDefaults.buttonColors(
                disabledContainerColor =
                    if (state.saving) c.primaryContainer else c.surfaceContainerHigh,
                disabledContentColor =
                    if (state.saving) c.onPrimaryContainer else c.onSurfaceVariant,
            ),
    ) {
      if (state.saving) ReviewSavingIndicator()
      Text(stringResource(state.actionTitle()), style = MaterialTheme.typography.labelLarge)
    }
    Label(
        if (isDemo) R.string.order_submit_help else R.string.order_persistent_help,
        11,
        color = c.onSurfaceVariant,
    )
  }
}

/** Preserves the saving animation while keeping the primary action focused on submission. */
@Composable
private fun ReviewSavingIndicator() {
  val rotation by
      rememberInfiniteTransition(label = "saving")
          .animateFloat(
              0f,
              360f,
              infiniteRepeatable(tween(900, easing = LinearEasing)),
              label = "spinner",
          )
  Image(painterResource(R.drawable.order_spinner), null, Modifier.size(24.dp).rotate(rotation))
  Spacer(Modifier.width(10.dp))
}

@Composable
private fun ProductRow(
    item: OrderItem,
    enabled: Boolean,
    onQuantity: (String, Int) -> Unit,
) {
  val c = MaterialTheme.colorScheme
  Row(
      Modifier.fillMaxWidth()
          .background(c.surfaceContainerLow, RoundedCornerShape(16.dp))
          .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    OrderIcon(R.drawable.order_bag, 24)
    Column(Modifier.weight(1f)) {
      Label(item.name, 15, true)
      Label(stringResource(R.string.order_each, money(item.unitPriceCents)), 12, true, c.primary)
    }
    Row(
        Modifier.background(c.surfaceContainerHighest, CircleShape).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      QuantityButton(item, -1, enabled, onQuantity)
      Label(item.quantity.toString(), 15, true)
      QuantityButton(item, 1, enabled && item.quantity < Int.MAX_VALUE, onQuantity)
    }
  }
}

@Composable
private fun QuantityButton(
    item: OrderItem,
    delta: Int,
    enabled: Boolean,
    onQuantity: (String, Int) -> Unit,
) {
  val c = MaterialTheme.colorScheme
  val action = if (delta < 0) "decrease" else "increase"
  val label =
      stringResource(if (delta < 0) R.string.order_decrease else R.string.order_increase, item.name)
  IconButton(
      { onQuantity(item.productId, delta) },
      enabled = enabled,
      modifier =
          Modifier.size(30.dp, 32.dp).testTag("${action}_${item.productId}").semantics {
            contentDescription = label
          },
  ) {
    Text(
        if (delta < 0) "−" else "+",
        color = if (delta < 0) c.onSurface else c.primary,
        fontSize = 20.sp,
    )
  }
}

@Composable
private fun CommissionField(state: ReviewOrderState, onChange: (String) -> Unit) {
  val c = MaterialTheme.colorScheme
  val invalid = state.commissionCents == null
  Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
    Label(R.string.order_commission, 13, true)
    OutlinedTextField(
        state.commission,
        onChange,
        enabled = state.editable,
        singleLine = true,
        isError = invalid,
        prefix = { Text("CHF") },
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.labelMedium.copy(textAlign = TextAlign.End),
        colors =
            OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = c.surfaceContainer,
                unfocusedBorderColor = c.outlineVariant,
            ),
        modifier = Modifier.fillMaxWidth().testTag(ReviewOrderTags.COMMISSION),
    )
    if (invalid) Label(R.string.order_commission_invalid, 11, color = c.error)
  }
}

@Composable
private fun Panel(color: Color, content: @Composable ColumnScope.() -> Unit) {
  Column(
      Modifier.fillMaxWidth().background(color, RoundedCornerShape(16.dp)).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      content = content,
  )
}

@Composable
private fun TotalRow(label: Int, cents: Int?, total: Boolean = false) {
  Row(
      Modifier.fillMaxWidth()
          .then(if (total) Modifier.testTag(ReviewOrderTags.TOTAL) else Modifier)
          .semantics(mergeDescendants = true) {},
      horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Label(stringResource(label), if (total) 16 else 13, total)
    Label(
        money(cents),
        if (total) 16 else 13,
        true,
        if (total) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun Label(
    text: String,
    size: Int,
    bold: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
  Text(
      text,
      style =
          MaterialTheme.typography.bodySmall.copy(
              fontSize = size.sp,
              lineHeight = (size * 1.4).sp,
              fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
          ),
      color = color,
  )
}

@Composable
private fun Label(
    id: Int,
    size: Int,
    bold: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
  Label(stringResource(id), size, bold, color)
}

@Composable
private fun OrderIcon(id: Int, size: Int, description: String? = null) {
  Image(painterResource(id), description, Modifier.size(size.dp))
}
