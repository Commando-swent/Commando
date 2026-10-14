package com.android.sample.ui.orders

import com.android.sample.model.OrderDraft
import com.android.sample.model.OrderItem
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Inject one flow-scoped instance into Catalogue and Review. Clear after successful save only. */
class OrderDraftState(tripId: String) {
  private val mutableDraft =
      MutableStateFlow(OrderDraft(tripId.also { require(it.isNotBlank()) }, emptyList()))
  val draft = mutableDraft.asStateFlow()

  /** Reopening the same trip preserves selection; switching trips starts an empty draft. */
  fun startTrip(tripId: String) {
    require(tripId.isNotBlank())
    mutableDraft.update { if (it.tripId == tripId) it else OrderDraft(tripId, emptyList()) }
  }

  /** Adds or replaces a selected snapshot; repeated IDs never create duplicate rows. */
  fun addItem(item: OrderItem) {
    require(item.productId.isNotBlank() && item.name.isNotBlank())
    require(item.unitPriceCents >= 0 && item.quantity > 0)
    editItems { items ->
      if (items.any { it.productId == item.productId })
          items.map { if (it.productId == item.productId) item else it }
      else items + item
    }
  }

  fun updateQuantity(productId: String, quantity: Int) {
    require(quantity >= 0)
    if (quantity == 0) removeProduct(productId)
    else
        editItems { items ->
          items.map { if (it.productId == productId) it.copy(quantity = quantity) else it }
        }
  }

  fun removeProduct(productId: String) = editItems { items ->
    items.filterNot { it.productId == productId }
  }

  fun updateCommission(commissionCents: Int) {
    require(commissionCents >= 0)
    mutableDraft.update { it.copy(commissionCents = commissionCents) }
  }

  fun clear() {
    mutableDraft.update { OrderDraft(it.tripId, emptyList()) }
  }

  private fun editItems(edit: (List<OrderItem>) -> List<OrderItem>) {
    mutableDraft.update { it.copy(items = Collections.unmodifiableList(edit(it.items))) }
  }
}
