package com.android.sample.ui.orders

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import com.android.sample.data.repository.FakeOrderRepository
import com.android.sample.data.repository.OrderRepository
import com.android.sample.model.authentication.AuthRepository
import kotlinx.coroutines.flow.map

/** One order repository and draft per authenticated App entry, retained on rotation. */
class SessionOrderViewModel(
    private val userId: String,
    private val auth: AuthRepository,
    createRepository: (() -> String?) -> OrderRepository,
) : ViewModel() {
  private var active = true
  val repository = createRepository { auth.currentUser?.uid?.takeIf { active && it == userId } }

  fun currentUserId(): String? = auth.currentUser?.uid?.takeIf { active && it == userId }

  val sessionChanges =
      auth.observeAuthState().map { it?.uid?.takeIf { id -> active && id == userId } }

  val isDemo: Boolean
    get() = repository is FakeOrderRepository

  private var sharedDraft: OrderDraftState? = null

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
  }
}
