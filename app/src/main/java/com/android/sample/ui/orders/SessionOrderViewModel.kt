package com.android.sample.ui.orders

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import com.android.sample.data.repository.FakeOrderRepository
import com.android.sample.data.repository.OrderRepository
import com.android.sample.model.Order
import com.android.sample.model.OrderDraft
import com.android.sample.model.authentication.AuthRepository
import kotlinx.coroutines.flow.map

/** Session-scoped repository and carts, retained on rotation and cleared with the App entry. */
class SessionOrderViewModel(
    private val userId: String,
    private val auth: AuthRepository,
    createRepository: (() -> String?) -> OrderRepository,
) : ViewModel() {
  private var active = true
  val repository = createRepository(::currentUserId)

  fun currentUserId(): String? = auth.currentUser?.uid?.takeIf { active && it == userId }

  val sessionChanges =
      auth.observeAuthState().map { it?.uid?.takeIf { id -> active && id == userId } }

  val isDemo: Boolean
    get() = repository is FakeOrderRepository

  private var sharedDraft: OrderDraftState? = null
  private val editDrafts = mutableMapOf<String, OrderDraftState>()

  /** Separate drafts by persisted order ID so editing cannot replace a new-order basket. */
  fun draftForEdit(order: Order): OrderDraftState {
    check(currentUserId() == order.requesterId) { "Order session has ended" }
    return editDrafts.getOrPut(order.id) {
      OrderDraftState(
          order.tripId,
          OrderDraft(order.tripId, order.items, order.commissionCents),
          order.revision,
      )
    }
  }

  /** Drops a completed or abandoned edit without affecting a new-order basket. */
  fun finishEdit(orderId: String) {
    editDrafts.remove(orderId)?.clear()
  }

  /** Catalogue and Review must use this same instance, including when returning with Back. */
  fun draftFor(tripId: String): OrderDraftState {
    check(active && auth.currentUser?.uid == userId) { "Order session has ended" }
    return sharedDraft?.also { it.startTrip(tripId) }
        ?: OrderDraftState(tripId).also { sharedDraft = it }
  }

  override fun onCleared() {
    active = false
    sharedDraft?.clear()
    sharedDraft = null
    editDrafts.values.forEach { it.clear() }
    editDrafts.clear()
  }
}
