package com.android.sample.ui.catalogue

// AI assistance: Claude Code.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.MAX_PRODUCT_QUERY_LIMIT
import com.android.sample.data.repository.MAX_SEARCH_QUERY_LENGTH
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
      /** Exact stored [Product.category] these results are limited to; null for every category. */
      val category: String? = null,
  ) : CatalogueContent {
    val canLoadMore: Boolean
      get() = nextCursor != null && !isLoadingMore
  }

  /**
   * [query] has no word the index can search, such as a single letter, or is too long: a hint, not
   * "no match".
   */
  data class UnsearchableQuery(val query: String) : CatalogueContent {
    val isTooLong: Boolean
      get() = query.length > MAX_SEARCH_QUERY_LENGTH
  }

  data class UnsupportedStore(val storeName: String) : CatalogueContent

  data class TripFailed(val error: TripError) : CatalogueContent

  data class ProductsFailed(val error: ProductError) : CatalogueContent
}

enum class CatalogueTab {
  CATALOGUE,
  FAVOURITES,
}

sealed interface FavouritesContent {
  data object Loading : FavouritesContent

  /** The trip's store does not have a supported catalogue. */
  data object Unsupported : FavouritesContent

  data class Loaded(
      val products: List<Product>,
      val unavailableProductIds: List<String> = emptyList(),
  ) : FavouritesContent {
    val count: Int
      get() = products.size + unavailableProductIds.size

    fun contains(productId: String): Boolean =
        products.any { it.id == productId } || productId in unavailableProductIds
  }

  data class Failed(val error: ProductError) : FavouritesContent
}

/** One-shot feedback after a favourite change. Only a successful removal can be undone. */
sealed interface FavouriteNotice {
  val productId: String

  data class Added(override val productId: String) : FavouriteNotice

  data class Removed(override val productId: String) : FavouriteNotice

  data class Failed(override val productId: String) : FavouriteNotice
}

data class CatalogueUiState(
    val trip: Trip? = null,
    val content: CatalogueContent = CatalogueContent.Loading,
    val query: String = "",
    /** Mirror of the shared draft's items; the draft stays the single source of truth. */
    val items: List<OrderItem> = emptyList(),
    /** Stored category values of the trip's chain, as `getCategories` returns them. */
    val categories: List<String> = emptyList(),
    /** Exact stored value the catalogue is filtered on; null shows every category. */
    val category: String? = null,
    val tab: CatalogueTab = CatalogueTab.CATALOGUE,
    val favourites: FavouritesContent = FavouritesContent.Loading,
    /** Product IDs whose optimistic favourite change is still being saved. */
    val pendingFavourites: Set<String> = emptySet(),
    val favouriteNotice: FavouriteNotice? = null,
) {
  val products: List<Product>
    get() = (content as? CatalogueContent.Loaded)?.products.orEmpty()

  val storeId: String?
    get() = trip?.let { storeIdFor(it.store.name) }

  val showsFavourites: Boolean
    get() = storeId != null && favourites !is FavouritesContent.Unsupported

  fun isFavourite(productId: String): Boolean =
      (favourites as? FavouritesContent.Loaded)?.contains(productId) == true

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

internal const val SEARCH_DEBOUNCE_MILLIS = 300L

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
  private var categoriesJob: Job? = null
  private var favouritesJob: Job? = null
  private var categoriesFailed = false
  private val removedFavourites = mutableMapOf<String, FavouriteEntry>()

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

  /** Filters on one stored [category] value, or none for null, from the first page. */
  fun selectCategory(category: String?) {
    if (category == mutableState.value.category) return
    mutableState.update { it.copy(category = category) }
    search(debounce = false)
  }

  fun selectTab(tab: CatalogueTab) {
    if (tab == CatalogueTab.FAVOURITES && !mutableState.value.showsFavourites) return
    mutableState.update { it.copy(tab = tab) }
  }

  /** Retries a failed favourite load without issuing duplicate reads. */
  fun retryFavourites() {
    val state = mutableState.value
    if (state.favourites !is FavouritesContent.Failed || favouritesJob?.isActive == true) return
    state.storeId?.let(::loadFavourites)
  }

  /** Optimistically toggles one product; repeated taps wait for the current write to finish. */
  fun toggleFavourite(productId: String) {
    val state = mutableState.value
    val favourites = state.favourites as? FavouritesContent.Loaded ?: return
    if (productId in state.pendingFavourites) return
    val entry =
        favourites.products.find { it.id == productId }?.let(::FavouriteEntry)
            ?: if (productId in favourites.unavailableProductIds) FavouriteEntry(null)
            else state.products.find { it.id == productId }?.let(::FavouriteEntry) ?: return
    changeFavourite(productId, favourite = !favourites.contains(productId), entry)
  }

  /** Restores the removal represented by [notice], if it is still the current feedback. */
  fun undoFavourite(notice: FavouriteNotice) {
    if (notice !is FavouriteNotice.Removed || mutableState.value.favouriteNotice != notice) return
    val entry = removedFavourites[notice.productId] ?: return
    mutableState.update { it.copy(favouriteNotice = null) }
    changeFavourite(notice.productId, favourite = true, entry, reportSuccess = false)
  }

  fun dismissFavouriteNotice(notice: FavouriteNotice) {
    mutableState.update { state ->
      if (state.favouriteNotice == notice) state.copy(favouriteNotice = null) else state
    }
  }

  /**
   * Repeats a failed first page; ignored while a search is already running or once products are
   * loaded, so it never drops them. A failed later page is retried through [loadMore].
   */
  fun retry() {
    if (searchJob?.isActive == true || mutableState.value.content is CatalogueContent.Loaded) return
    search(debounce = false)
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
            productRepository.searchProducts(
                storeId,
                loaded.query,
                cursor,
                MAX_PRODUCT_QUERY_LIMIT,
                loaded.category,
            )
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
    val state = mutableState.value
    val favouriteProducts = (state.favourites as? FavouritesContent.Loaded)?.products.orEmpty()
    val product = (state.products + favouriteProducts).find { it.id == productId } ?: return
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
    val category = mutableState.value.category
    if (!debounce) mutableState.update { it.copy(content = CatalogueContent.Loading) }
    searchJob = viewModelScope.launch {
      // While typing, the current results stay until the search is actually sent.
      if (debounce) {
        delay(searchDebounceMillis)
        mutableState.update { it.copy(content = CatalogueContent.Loading) }
      }
      val content =
          try {
            loadFirstPage(query, category)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            CatalogueContent.ProductsFailed(ProductError.Unknown)
          }
      // The query changed while this request ran: its results are stale.
      ensureActive()
      mutableState.update { it.copy(content = content) }
      // The store is reachable again: read categories that failed beside an earlier request.
      val storeId = mutableState.value.storeId
      if (content is CatalogueContent.Loaded && categoriesFailed && storeId != null) {
        loadCategories(storeId)
      }
    }
  }

  private suspend fun loadFirstPage(query: String, category: String?): CatalogueContent {
    val trip =
        mutableState.value.trip
            ?: when (val result = tripRepository.getTripById(tripId)) {
              is TripResult.Success -> result.data.also(::onTripLoaded)
              is TripResult.Error -> return CatalogueContent.TripFailed(result.error)
            }
    val storeId =
        storeIdFor(trip.store.name) ?: return CatalogueContent.UnsupportedStore(trip.store.name)
    val result =
        productRepository.searchProducts(
            storeId,
            query,
            cursor = null,
            MAX_PRODUCT_QUERY_LIMIT,
            category,
        )
    return when (result) {
      // Never show, or let the requester select, a product from another chain.
      is ProductResult.Success ->
          CatalogueContent.Loaded(
              result.data.products.filter { it.belongsTo(storeId) }.distinctBy { it.id },
              query,
              result.data.nextCursor,
              category = category,
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

  /** Loads category and favourite metadata once beside the first page for a supported trip. */
  private fun onTripLoaded(trip: Trip) {
    val storeId = storeIdFor(trip.store.name)
    mutableState.update {
      it.copy(
          trip = trip,
          favourites =
              if (storeId == null) FavouritesContent.Unsupported else FavouritesContent.Loading,
      )
    }
    if (storeId == null) return
    loadCategories(storeId)
    loadFavourites(storeId)
  }

  /** Without categories there are no chips; a failed read is retried after a later search loads. */
  private fun loadCategories(storeId: String) {
    if (categoriesJob?.isActive == true) return
    categoriesFailed = false
    categoriesJob = viewModelScope.launch {
      val categories =
          try {
            (productRepository.getCategories(storeId) as? ProductResult.Success)?.data
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            null
          }
      categoriesFailed = categories == null
      mutableState.update { it.copy(categories = categories.orEmpty().distinct()) }
    }
  }

  private fun loadFavourites(storeId: String) {
    if (favouritesJob?.isActive == true) return
    mutableState.update { it.copy(favourites = FavouritesContent.Loading) }
    favouritesJob = viewModelScope.launch {
      val result =
          try {
            productRepository.getFavouriteProducts(storeId)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            ProductResult.Error(ProductError.Unknown)
          }
      ensureActive()
      val content =
          when (result) {
            is ProductResult.Success ->
                FavouritesContent.Loaded(
                    result.data.products.filter { it.belongsTo(storeId) }.distinctBy { it.id },
                    result.data.unavailableProductIds
                        .filter { it.startsWith("$storeId:") }
                        .distinct(),
                )
            is ProductResult.Error ->
                if (result.error == ProductError.InvalidStore) FavouritesContent.Unsupported
                else FavouritesContent.Failed(result.error)
          }
      mutableState.update {
        it.copy(
            favourites = content,
            tab = if (content is FavouritesContent.Unsupported) CatalogueTab.CATALOGUE else it.tab,
        )
      }
    }
  }

  private fun changeFavourite(
      productId: String,
      favourite: Boolean,
      entry: FavouriteEntry,
      reportSuccess: Boolean = true,
  ) {
    val storeId = mutableState.value.storeId ?: return
    mutableState.update {
      it.copy(
          favourites = it.favourites.withFavourite(productId, favourite, entry),
          pendingFavourites = it.pendingFavourites + productId,
          favouriteNotice = null,
      )
    }
    viewModelScope.launch {
      val result =
          try {
            productRepository.setFavourite(storeId, productId, favourite)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            ProductResult.Error(ProductError.Unknown)
          }
      ensureActive()
      finishFavouriteChange(
          productId,
          favourite,
          entry,
          result is ProductResult.Success,
          reportSuccess,
      )
    }
  }

  /** Keeps a saved change, or rolls a failed one back, and reports the outcome. */
  private fun finishFavouriteChange(
      productId: String,
      favourite: Boolean,
      entry: FavouriteEntry,
      saved: Boolean,
      reportSuccess: Boolean,
  ) {
    if (saved && favourite) removedFavourites.remove(productId)
    if (saved && !favourite) removedFavourites[productId] = entry
    mutableState.update {
      it.copy(
          favourites =
              if (saved) it.favourites
              else it.favourites.withFavourite(productId, !favourite, entry),
          pendingFavourites = it.pendingFavourites - productId,
          favouriteNotice =
              when {
                !saved -> FavouriteNotice.Failed(productId)
                !reportSuccess -> null
                favourite -> FavouriteNotice.Added(productId)
                else -> FavouriteNotice.Removed(productId)
              },
      )
    }
  }

  private fun FavouritesContent.withFavourite(
      productId: String,
      favourite: Boolean,
      entry: FavouriteEntry,
  ): FavouritesContent {
    val loaded = this as? FavouritesContent.Loaded ?: return this
    if (!favourite) {
      return loaded.copy(
          products = loaded.products.filterNot { it.id == productId },
          unavailableProductIds = loaded.unavailableProductIds - productId,
      )
    }
    if (loaded.contains(productId)) return loaded
    val product = entry.product
    return if (product != null)
        loaded.copy(products = (loaded.products + product).sortedBy { it.id })
    else loaded.copy(unavailableProductIds = (loaded.unavailableProductIds + productId).sorted())
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

  private data class FavouriteEntry(val product: Product?)
}
