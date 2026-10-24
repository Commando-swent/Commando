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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CatalogueContent {
  data object Loading : CatalogueContent

  /** Products of the trip's chain only; an empty list is a real, empty catalogue. */
  data class Loaded(val products: List<Product>) : CatalogueContent

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

  val visibleProducts: List<Product>
    get() {
      val search = query.trim()
      return if (search.isEmpty()) products
      else products.filter { it.name.contains(search, ignoreCase = true) }
    }

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

/**
 * Loads the selected trip's chain catalogue and edits the shared [OrderDraftState]. Pass the same
 * draft instance to Review Order; this screen never clears it.
 */
class CatalogueViewModel(
    private val tripId: String,
    private val tripRepository: TripRepository,
    private val productRepository: ProductRepository,
    private val draft: OrderDraftState,
) : ViewModel() {
  private val mutableState: MutableStateFlow<CatalogueUiState>
  val uiState: StateFlow<CatalogueUiState>
  private var isLoading = false

  init {
    require(tripId.isNotBlank())
    // Reopening the same trip keeps its selection; another trip starts an empty draft.
    draft.startTrip(tripId)
    mutableState = MutableStateFlow(CatalogueUiState(items = itemsOf(draft.draft.value)))
    uiState = mutableState.asStateFlow()
    viewModelScope.launch {
      draft.draft.collect { current -> mutableState.update { it.copy(items = itemsOf(current)) } }
    }
    load()
  }

  fun onQueryChange(query: String) {
    mutableState.update { it.copy(query = query) }
  }

  fun clearSearch() = onQueryChange("")

  fun retry() = load()

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

  private fun load() {
    if (isLoading) return
    // Set this before launching so repeated calls cannot start concurrent requests.
    isLoading = true
    mutableState.update { it.copy(content = CatalogueContent.Loading) }
    viewModelScope.launch {
      try {
        val content = loadContent()
        mutableState.update { it.copy(content = content) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update {
          it.copy(content = CatalogueContent.ProductsFailed(ProductError.Unknown))
        }
      } finally {
        isLoading = false
      }
    }
  }

  private suspend fun loadContent(): CatalogueContent {
    val trip =
        mutableState.value.trip
            ?: when (val result = tripRepository.getTripById(tripId)) {
              is TripResult.Success ->
                  result.data.also { trip -> mutableState.update { it.copy(trip = trip) } }
              is TripResult.Error -> return CatalogueContent.TripFailed(result.error)
            }
    val storeId =
        storeIdFor(trip.store.name) ?: return CatalogueContent.UnsupportedStore(trip.store.name)
    return when (val result = productRepository.getProducts(storeId, MAX_PRODUCT_QUERY_LIMIT)) {
      // Never show, or let the requester select, a product from another chain.
      is ProductResult.Success ->
          CatalogueContent.Loaded(result.data.filter { it.belongsTo(storeId) })
      is ProductResult.Error ->
          if (result.error == ProductError.InvalidStore)
              CatalogueContent.UnsupportedStore(trip.store.name)
          else CatalogueContent.ProductsFailed(result.error)
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
