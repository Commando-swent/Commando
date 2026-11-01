package com.android.sample.ui.orders

// AI assistance: Claude Code.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.OrderError
import com.android.sample.data.repository.OrderRepository
import com.android.sample.data.repository.OrderResult
import com.android.sample.data.repository.ShoppingChecklistRepository
import com.android.sample.model.ShoppingChecklistItem
import com.android.sample.model.buildShoppingChecklist
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** What the shopping checklist of one trip shows. */
sealed class ShoppingChecklistUiState {
  /** A load is in progress; previous items are no longer shown. */
  data object Loading : ShoppingChecklistUiState()

  /** The products to buy; [saveError] is set when the last toggle could not be saved. */
  data class Content(
      val items: List<ShoppingChecklistItem>,
      val saveError: OrderError? = null,
  ) : ShoppingChecklistUiState()

  /** No accepted order on this trip yet. */
  data object Empty : ShoppingChecklistUiState()

  /** The load failed with [error]; [ShoppingChecklistViewModel.refresh] retries it. */
  data class Error(val error: OrderError) : ShoppingChecklistUiState()
}

/**
 * Shopping checklist of [tripId] for its owner: products of the accepted orders from
 * [orderRepository], with the purchase progress saved in [checklistRepository]. Only the latest
 * load may publish, and a load first waits for the saves already sent, so it never shows progress
 * older than a successful toggle. Results of an ended session are refused by the repository.
 * Nothing loads until [refresh] is called; navigation calls it whenever the screen resumes.
 */
class ShoppingChecklistViewModel(
    private val orderRepository: OrderRepository,
    private val checklistRepository: ShoppingChecklistRepository,
    private val tripId: String,
) : ViewModel() {
  private val mutableState =
      MutableStateFlow<ShoppingChecklistUiState>(ShoppingChecklistUiState.Loading)
  /** Current state of the checklist, read by the screen. */
  val uiState: StateFlow<ShoppingChecklistUiState> = mutableState.asStateFlow()
  private var loadJob: Job? = null
  private var latestLoad = 0
  private val pendingSaves = mutableSetOf<Job>()

  /** Reloads orders and progress, e.g. on retry or when newly accepted orders may exist. */
  fun refresh() {
    loadJob?.cancel()
    val load = ++latestLoad
    mutableState.value = ShoppingChecklistUiState.Loading
    val saves = pendingSaves.toList()
    loadJob = viewModelScope.launch {
      // Reading before a pending save commits would show the item with its old state.
      saves.joinAll()
      val state = fetch()
      if (load == latestLoad) mutableState.value = state
    }
  }

  /**
   * Saves [item] as purchased or not; the item changes only once the save succeeded. Toggles send
   * the new value, so a repeated tap before the reply writes the same value again.
   */
  fun setPurchased(item: ShoppingChecklistItem, purchased: Boolean) {
    if (mutableState.value !is ShoppingChecklistUiState.Content) return
    val save = viewModelScope.launch {
      val result = checklistRepository.setPurchased(tripId, item, purchased)
      // While a refresh is loading, it waits for this save and reads its result itself.
      val content = mutableState.value as? ShoppingChecklistUiState.Content ?: return@launch
      mutableState.value =
          when (result) {
            is OrderResult.Error -> content.copy(saveError = result.error)
            is OrderResult.Success ->
                ShoppingChecklistUiState.Content(
                    content.items.map {
                      if (it.productId == item.productId) it.copy(purchased = purchased) else it
                    }
                )
          }
    }
    pendingSaves += save
    save.invokeOnCompletion { pendingSaves -= save }
  }

  private suspend fun fetch(): ShoppingChecklistUiState =
      try {
        val orders =
            when (val result = orderRepository.getOrdersForTrip(tripId)) {
              is OrderResult.Error -> return ShoppingChecklistUiState.Error(result.error)
              is OrderResult.Success -> result.data
            }
        val progress =
            when (val result = checklistRepository.getPurchasedQuantities(tripId)) {
              is OrderResult.Error -> return ShoppingChecklistUiState.Error(result.error)
              is OrderResult.Success -> result.data
            }
        val items = buildShoppingChecklist(tripId, orders, progress)
        if (items.isEmpty()) ShoppingChecklistUiState.Empty
        else ShoppingChecklistUiState.Content(items)
      } catch (_: Exception) {
        // Rethrows if this load was cancelled; any other failure becomes a retryable error.
        currentCoroutineContext().ensureActive()
        ShoppingChecklistUiState.Error(OrderError.Unknown)
      }
}
