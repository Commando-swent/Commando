package com.android.sample.ui.orders

// AI assistance: Claude Code for the Long CHF formatter (#147).
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.*
import com.android.sample.model.*
import java.math.BigDecimal
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal fun commissionCents(text: String): Int? =
    text
        .trim()
        .replace(',', '.')
        .takeIf { it.matches(Regex("[0-9]+(\\.[0-9]{1,2})?")) }
        ?.let { runCatching { BigDecimal(it).movePointRight(2).intValueExact() }.getOrNull() }

private const val CENTS_PER_FRANC = 100

/** Formats optional CHF [cents]; a missing amount shows a dash. */
internal fun money(cents: Int?) = cents?.let { money(it.toLong()) } ?: "—"

/** Formats CHF [cents] as `CHF x.yy`; Long, so an order total cannot overflow. */
internal fun money(cents: Long): String =
    String.format(Locale.ROOT, "CHF %d.%02d", cents / CENTS_PER_FRANC, cents % CENTS_PER_FRANC)

data class ReviewOrderState(
    val draft: OrderDraft,
    val commission: String,
    val saving: Boolean = false,
    val error: OrderError? = null,
    val order: Order? = null,
    val tripMatches: Boolean = true,
) {
  val commissionCents
    get() = commissionCents(commission)

  val productsCents
    get() = runCatching {
      draft.items.fold(0) { sum, item ->
        require(item.quantity > 0 && item.unitPriceCents >= 0)
        Math.addExact(sum, Math.multiplyExact(item.unitPriceCents, item.quantity))
      }
    }
        .getOrNull()

  val totalCents
    get() = commissionCents?.let { fee ->
      productsCents?.let { runCatching { Math.addExact(it, fee) }.getOrNull() }
    }

  val editable
    get() = !saving && order == null && tripMatches

  val hasTooManyProducts: Boolean
    get() = draft.items.size > MAX_ORDER_PRODUCTS

  val canSubmit
    get() =
        editable &&
            draft.tripId.isNotBlank() &&
            draft.items.isNotEmpty() &&
            !hasTooManyProducts &&
            totalCents != null
}

/** Catalogue and Review receive the SAME draft holder. There is no second editable cart. */
class ReviewOrderViewModel(
    private val repository: OrderRepository,
    private val shared: OrderDraftState,
) : ViewModel() {
  private val initial = shared.draft.value
  private val tripId = initial.tripId
  private val form =
      MutableStateFlow(
          ReviewOrderState(initial, money(initial.commissionCents).removePrefix("CHF "))
      )
  val uiState = form.asStateFlow()

  init {
    viewModelScope.launch {
      shared.draft.collect { draft ->
        form.update { state ->
          state.copy(
              draft = if (state.order == null) draft else state.draft,
              tripMatches = draft.tripId == tripId,
          )
        }
      }
    }
  }

  fun updateCommission(value: String) {
    if (!editable()) return
    form.update { it.copy(commission = value, error = null) }
    commissionCents(value)?.let(shared::updateCommission)
  }

  fun changeQuantity(productId: String, delta: Int) {
    if (!editable() || (delta != -1 && delta != 1)) return
    val item = shared.draft.value.items.find { it.productId == productId } ?: return
    if (delta == 1 && item.quantity == Int.MAX_VALUE) return
    shared.updateQuantity(productId, item.quantity + delta)
    form.update { it.copy(error = null) }
  }

  fun submit() {
    if (!editable()) return
    val current = form.value.copy(draft = shared.draft.value)
    if (!current.canSubmit) return
    val input = current.draft.copy(commissionCents = checkNotNull(current.commissionCents))
    form.value = current.copy(saving = true, error = null)
    viewModelScope.launch {
      try {
        when (val result = repository.createOrder(input)) {
          is OrderResult.Success -> {
            if (shared.draft.value == input) shared.clear()
            form.update { it.copy(order = result.data, draft = input) }
          }
          is OrderResult.Error -> form.update { it.copy(error = result.error) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        form.update { it.copy(error = OrderError.Unknown) }
      } finally {
        form.update { it.copy(saving = false) }
      }
    }
  }

  private fun editable() = form.value.editable && shared.draft.value.tripId == tripId
}
