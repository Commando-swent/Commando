package com.android.sample.ui.orders

// AI assistance: OpenAI Codex (#232).
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.sample.R
import com.android.sample.data.repository.TripRepository

/**
 * Restores edit identity from navigation, never from a transient UI flag. On recreation it reloads
 * the authorized order before seeding the shared Catalogue/Review draft; new-order carts stay
 * separate.
 */
@Composable
internal fun OrderFlowDraft(
    orders: SessionOrderViewModel,
    trips: TripRepository,
    tripId: String,
    editOrderId: String?,
    onBack: () -> Unit,
    content: @Composable (OrderDraftState) -> Unit,
) {
  if (editOrderId == null) {
    content(orders.draftFor(tripId))
    return
  }
  // Reuse the session-aware order/trip loader so edit entry follows the same access checks.
  val source: OrderDetailsViewModel =
      viewModel(key = "edit-source-$editOrderId") {
        OrderDetailsViewModel(
            editOrderId,
            orders.repository,
            trips,
            orders::currentUserId,
            orders.sessionChanges,
        )
      }
  val state by source.uiState.collectAsState()
  val loaded = state as? OrderDetailsUiState.Content
  if (loaded != null && loaded.canEdit && loaded.order.tripId == tripId) {
    content(orders.draftForEdit(loaded.order))
  } else {
    OrderDetailsScreen(
        state,
        onBack,
        source::retry,
        notice = if (loaded != null) stringResource(R.string.order_edit_unavailable) else null,
    )
  }
}
