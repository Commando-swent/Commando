package com.android.sample.ui.orders

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import com.android.sample.data.repository.OrderRepository
import com.android.sample.model.authentication.AuthRepository

/** One in-memory order store and draft per authenticated App entry, retained on rotation. */
class SessionOrderViewModel(
    private val userId: String,
    private val auth: AuthRepository,
    createRepository: (() -> String?) -> OrderRepository,
) : ViewModel() {
  private var active = true
  val repository = createRepository { auth.currentUser?.uid?.takeIf { active && it == userId } }
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
