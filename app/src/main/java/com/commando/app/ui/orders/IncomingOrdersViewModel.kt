package com.commando.app.ui.orders

// AI assistance: Claude Code.
// AI assistance: OpenAI Codex for public requester-name resolution.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.commando.app.data.repository.OrderError
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.PublicProfileRepository
import com.commando.app.model.Order
import com.commando.app.model.itemCount
import com.commando.app.model.totalCents
import com.commando.app.ui.session.SessionGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * One stored order plus its requester's explicitly public name, or null for the localized fallback.
 */
data class IncomingOrderRow(val order: Order, val requesterName: String? = null) {
  /** Total quantity across products; see [Order.itemCount]. */
  val itemCount: Long
    get() = order.itemCount

  /** Products plus commission, in CHF cents; see [Order.totalCents]. */
  val totalCents: Long
    get() = order.totalCents
}

/** What the incoming-orders list of one trip shows. */
sealed class IncomingOrdersUiState {
  /** A load is in progress; previous rows are no longer shown. */
  data object Loading : IncomingOrdersUiState()

  /** The trip's orders, newest first, as returned by the repository. */
  data class Content(val rows: List<IncomingOrderRow>) : IncomingOrdersUiState()

  /** The trip has no orders yet. */
  data object Empty : IncomingOrdersUiState()

  /** The load failed with [error]; [IncomingOrdersViewModel.refresh] retries it. */
  data class Error(val error: OrderError) : IncomingOrdersUiState()
}

/**
 * Lists the orders of [tripId] for its owner. The repository alone decides which orders it returns.
 * Each load clears the previous rows at once and only the latest load may publish, so a late reply
 * (for example from before an account change) never reappears.
 *
 * @param loadImmediately false when navigation starts the first load itself, e.g. on resume.
 */
class IncomingOrdersViewModel(
    private val orderRepository: OrderRepository,
    private val tripId: String,
    loadImmediately: Boolean = true,
    private val publicProfiles: Lazy<PublicProfileRepository>? = null,
    currentUserId: (() -> String?)? = null,
    sessionChanges: Flow<String?> = emptyFlow(),
) : ViewModel() {
  private val mutableState = MutableStateFlow<IncomingOrdersUiState>(IncomingOrdersUiState.Loading)
  /** Current state of the list, read by the screen. */
  val uiState: StateFlow<IncomingOrdersUiState> = mutableState.asStateFlow()
  private var loadJob: Job? = null
  private var latestLoad = 0
  private val session = currentUserId?.let(::SessionGuard)

  init {
    viewModelScope.launch {
      sessionChanges.collect { uid ->
        if (session?.hasEnded(uid) == true) {
          loadJob?.cancel()
          latestLoad++
          mutableState.value = IncomingOrdersUiState.Error(OrderError.PermissionDenied)
        }
      }
    }
    // Navigation may own the first load through ON_RESUME, which also refreshes after Details.
    if (loadImmediately) refresh()
  }

  /**
   * Reloads orders and invalidates all public names on retry/resume. One lookup per distinct
   * requester runs in this load; missing names and failures are retried on the next refresh, never
   * cached globally.
   */
  fun refresh() {
    loadJob?.cancel()
    // A repository may still return after cancellation; the counter discards superseded results.
    val load = ++latestLoad
    if (session?.hasEnded() == true) {
      mutableState.value = IncomingOrdersUiState.Error(OrderError.PermissionDenied)
      return
    }
    mutableState.value = IncomingOrdersUiState.Loading
    val job =
        viewModelScope.launch(start = CoroutineStart.LAZY) {
          val state = fetch()
          ensureActive()
          if (load != latestLoad || session?.hasEnded() == true) return@launch
          mutableState.value = state
          if (state is IncomingOrdersUiState.Content) resolveRequesterNames(state)
        }
    loadJob = job
    job.start()
  }

  private suspend fun resolveRequesterNames(state: IncomingOrdersUiState.Content) = coroutineScope {
    val profiles = publicProfiles ?: return@coroutineScope
    // Names belong to this load; child jobs cannot block the initial order rendering.
    val resolvedNames = mutableMapOf<String, String?>()
    state.rows
        .map { it.order.requesterId }
        .distinct()
        .forEach { requesterId ->
          launch {
            val name =
                try {
                  profiles.value.getDisplayName(requesterId)
                } catch (cancelled: CancellationException) {
                  throw cancelled
                } catch (_: Exception) {
                  null
                }
            ensureActive()
            if (session?.hasEnded() == true) return@launch
            resolvedNames[requesterId] = name
            val rows =
                state.rows.map { row ->
                  row.copy(requesterName = resolvedNames[row.order.requesterId])
                }
            mutableState.value = state.copy(rows = rows)
          }
        }
  }

  private suspend fun fetch(): IncomingOrdersUiState =
      try {
        when (val result = orderRepository.getOrdersForTrip(tripId)) {
          is OrderResult.Error -> IncomingOrdersUiState.Error(result.error)
          is OrderResult.Success -> {
            val rows = result.data.map { IncomingOrderRow(it) }
            if (rows.isEmpty()) IncomingOrdersUiState.Empty else IncomingOrdersUiState.Content(rows)
          }
        }
      } catch (_: Exception) {
        // Rethrows if this load was cancelled; any other failure becomes a retryable error.
        currentCoroutineContext().ensureActive()
        IncomingOrdersUiState.Error(OrderError.Unknown)
      }
}
