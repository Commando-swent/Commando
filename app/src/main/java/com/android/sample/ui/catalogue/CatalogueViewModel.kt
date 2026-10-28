package com.android.sample.ui.catalogue

// AI assistance: Claude Code.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.MAX_PRODUCT_QUERY_LIMIT
import com.android.sample.data.repository.ProductError
import com.android.sample.data.repository.ProductRepository
import com.android.sample.data.repository.ProductResult
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.MAX_ORDER_PRODUCTS
import com.android.sample.model.OrderDraft
import com.android.sample.model.OrderItem
import com.android.sample.model.Product
import com.android.sample.model.Trip
import com.android.sample.model.storeIdFor
import com.android.sample.ui.orders.OrderDraftState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CatalogueContent {
  data object Loading : CatalogueContent

  /**
   * Loaded pages of the trip's chain for [query], the trimmed search these results answer. An empty
   * list for a blank [query] is a real, empty catalogue. [nextCursor] is null once every page is
   * loaded; a [loadMoreError] keeps it so the failed page can be retried.
   */
  data class Loaded(
      val products: List<Product>,
      val query: String = "",
      val nextCursor: String? = null,
      val isLoadingMore: Boolean = false,
      val loadMoreError: ProductError? = null,
  ) : CatalogueContent {
    val canLoadMore: Boolean
      get() = nextCursor != null && !isLoadingMore
  }

  /** [query] has no word the index can search, such as a single letter: a hint, not "no match". */
  data class UnsearchableQuery(val query: String) : CatalogueContent

  data class UnsupportedStore(val storeName: String) : CatalogueContent

  data class TripFailed(val error: TripError) : CatalogueContent

  data class ProductsFailed(val error: ProductError) : CatalogueContent
}

data class CatalogueUiState(
    val trip: Trip? = null,
    val content: CatalogueContent = CatalogueContent.Loading,
    val query: String = "",
    /** Mirror of the shared draft's items; the draft stays the single source of truth. */
    val items: List<OrderItem> = emptyList(),
) {
  val products: List<Product>
    get() = (content as? CatalogueContent.Loaded)?.products.orEmpty()

  /** Searching needs the trip's chain; it stays possible after a failed search. */
  val canSearch: Boolean
    get() = trip != null && content !is CatalogueContent.UnsupportedStore

  val selectedCount: Long
    get() = items.sumOf { it.quantity.toLong() }

  /** Null when the total does not fit in an Int, matching the Review Order checks. */
  val estimatedCents: Int?
    get() = runCatching {
      items.fold(0) { sum, item ->
        Math.addExact(sum, Math.multiplyExact(item.unitPriceCents, item.quantity))
      }
    }
        .getOrNull()

  val hasTooManyProducts: Boolean
    get() = items.size > MAX_ORDER_PRODUCTS

  val canContinue: Boolean
    get() = trip != null && items.isNotEmpty() && !hasTooManyProducts && estimatedCents != null

  fun quantityOf(productId: String): Int = items.find { it.productId == productId }?.quantity ?: 0
}

const val SEARCH_DEBOUNCE_MILLIS = 300L

/**
 * Searches the selected trip's chain catalogue page by page and edits the shared [OrderDraftState].
 * Pass the same draft instance to Review Order; this screen never clears it.
 */
class CatalogueViewModel(
    private val tripId: String,
    private val tripRepository: TripRepository,
    private val productRepository: ProductRepository,
    private val draft: OrderDraftState,
    private val searchDebounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
) : ViewModel() {
  private val mutableState: MutableStateFlow<CatalogueUiState>
  val uiState: StateFlow<CatalogueUiState>
  private var searchJob: Job? = null
  private var pageJob: Job? = null

  init {
    require(tripId.isNotBlank())
    // Reopening the same trip keeps its selection; another trip starts an empty draft.
    draft.startTrip(tripId)
    mutableState = MutableStateFlow(CatalogueUiState(items = itemsOf(draft.draft.value)))
    uiState = mutableState.asStateFlow()
    viewModelScope.launch {
      draft.draft.collect { current -> mutableState.update { it.copy(items = itemsOf(current)) } }
    }
    search(debounce = false)
  }

  /** Each change restarts a debounced search; results of an older query are never shown. */
  fun onQueryChange(query: String) {
    val previous = mutableState.value.query
    mutableState.update { it.copy(query = query) }
    if (query.trim() != previous.trim()) search(debounce = true)
  }

  fun clearSearch() = onQueryChange("")

  /** Repeats a failed first page; ignored while a search is already running. */
  fun retry() {
    if (searchJob?.isActive != true) search(debounce = false)
  }

  /** Appends the next page, or retries the page that failed; one request at a time. */
  fun loadMore() {
    val loaded = mutableState.value.content as? CatalogueContent.Loaded ?: return
    val cursor = loaded.nextCursor
    val storeId = mutableState.value.trip?.let { storeIdFor(it.store.name) }
    // A pending search will replace these results; never extend them with another page.
    if (searchJob?.isActive == true) return
    if (!loaded.canLoadMore || cursor == null || storeId == null) return
    setLoaded { it.copy(isLoadingMore = true, loadMoreError = null) }
    pageJob = viewModelScope.launch {
      val result =
          try {
            productRepository.searchProducts(storeId, loaded.query, cursor, MAX_PRODUCT_QUERY_LIMIT)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            ProductResult.Error(ProductError.Unknown)
          }
      // A new search cancels this job; never append a page to another query's results.
      ensureActive()
      setLoaded { current ->
        when (result) {
          is ProductResult.Success -> {
            val known = current.products.mapTo(HashSet()) { it.id }
            val page = result.data.products.filter { it.belongsTo(storeId) && it.id !in known }
            current.copy(
                products = current.products + page,
                nextCursor = result.data.nextCursor,
                isLoadingMore = false,
            )
          }
          is ProductResult.Error ->
              current.copy(isLoadingMore = false, loadMoreError = result.error)
        }
      }
    }
  }

  fun add(productId: String) {
    if (!ownsDraft()) return
    if (quantityInDraft(productId) > 0) return increment(productId)
    val product = mutableState.value.products.find { it.id == productId } ?: return
    draft.addItem(OrderItem(product.id, product.name, product.priceCents, quantity = 1))
  }

  fun increment(productId: String) {
    val quantity = quantityInDraft(productId)
    if (!ownsDraft() || quantity == 0 || quantity == Int.MAX_VALUE) return
    draft.updateQuantity(productId, quantity + 1)
  }

  /** Decreasing from one removes the product from the draft. */
  fun decrement(productId: String) {
    val quantity = quantityInDraft(productId)
    if (!ownsDraft() || quantity == 0) return
    draft.updateQuantity(productId, quantity - 1)
  }

  private fun search(debounce: Boolean) {
    searchJob?.cancel()
    pageJob?.cancel()
    setLoaded { it.copy(isLoadingMore = false) }
    val query = mutableState.value.query.trim()
    if (!debounce) mutableState.update { it.copy(content = CatalogueContent.Loading) }
    searchJob = viewModelScope.launch {
      // While typing, the current results stay until the search is actually sent.
      if (debounce) {
        delay(searchDebounceMillis)
        mutableState.update { it.copy(content = CatalogueContent.Loading) }
      }
      val content =
          try {
            loadFirstPage(query)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            CatalogueContent.ProductsFailed(ProductError.Unknown)
          }
      // The query changed while this request ran: its results are stale.
      ensureActive()
      mutableState.update { it.copy(content = content) }
    }
  }

  private suspend fun loadFirstPage(query: String): CatalogueContent {
    val trip =
        mutableState.value.trip
            ?: when (val result = tripRepository.getTripById(tripId)) {
              is TripResult.Success ->
                  result.data.also { trip -> mutableState.update { it.copy(trip = trip) } }
              is TripResult.Error -> return CatalogueContent.TripFailed(result.error)
            }
    val storeId =
        storeIdFor(trip.store.name) ?: return CatalogueContent.UnsupportedStore(trip.store.name)
    val result =
        productRepository.searchProducts(storeId, query, cursor = null, MAX_PRODUCT_QUERY_LIMIT)
    return when (result) {
      // Never show, or let the requester select, a product from another chain.
      is ProductResult.Success ->
          CatalogueContent.Loaded(
              result.data.products.filter { it.belongsTo(storeId) }.distinctBy { it.id },
              query,
              result.data.nextCursor,
          )
      is ProductResult.Error ->
          when (result.error) {
            ProductError.InvalidStore -> CatalogueContent.UnsupportedStore(trip.store.name)
            // Too short or too long to search, such as "a": checked before any Firestore read.
            ProductError.InvalidQuery -> CatalogueContent.UnsearchableQuery(query)
            else -> CatalogueContent.ProductsFailed(result.error)
          }
    }
  }

  private fun setLoaded(edit: (CatalogueContent.Loaded) -> CatalogueContent.Loaded) {
    mutableState.update { state ->
      (state.content as? CatalogueContent.Loaded)?.let { state.copy(content = edit(it)) } ?: state
    }
  }

  private fun Product.belongsTo(storeId: String) =
      this.storeId == storeId && id.isNotBlank() && name.isNotBlank() && priceCents >= 0

  private fun itemsOf(current: OrderDraft) =
      if (current.tripId == tripId) current.items else emptyList()

  private fun ownsDraft() = draft.draft.value.tripId == tripId

  private fun quantityInDraft(productId: String) =
      itemsOf(draft.draft.value).find { it.productId == productId }?.quantity ?: 0
}
