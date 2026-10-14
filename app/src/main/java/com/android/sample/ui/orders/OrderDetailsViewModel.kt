package com.android.sample.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.OrderError
import com.android.sample.data.repository.OrderRepository
import com.android.sample.data.repository.OrderResult
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Order
import com.android.sample.model.Trip
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class OrderDetailsUiState {
  data object Loading : OrderDetailsUiState()

  data class Content(val order: Order, val trip: Trip) : OrderDetailsUiState()

  data object NotFound : OrderDetailsUiState()

  sealed class Error : OrderDetailsUiState() {
    data class OrderFailure(val error: OrderError) : Error()

    data class TripFailure(val error: TripError) : Error()
  }
}

/** Loads an authorized order before its trip; repositories own all access decisions. */
class OrderDetailsViewModel(
    private val orderId: String,
    private val orderRepository: OrderRepository,
    private val tripRepository: TripRepository,
) : ViewModel() {
  private val mutableState = MutableStateFlow<OrderDetailsUiState>(OrderDetailsUiState.Loading)
  val uiState: StateFlow<OrderDetailsUiState> = mutableState.asStateFlow()
  private var isLoading = false

  init {
    load()
  }

  fun retry() {
    load()
  }

  private fun load() {
    if (isLoading) return
    // Set before launching so rapid retries cannot start simultaneous loads.
    isLoading = true
    mutableState.value = OrderDetailsUiState.Loading
    viewModelScope.launch {
      try {
        val state = fetchOrder()
        // A repository may return even after the destination's coroutine was cancelled.
        currentCoroutineContext().ensureActive()
        mutableState.value = state
      } finally {
        isLoading = false
      }
    }
  }

  private suspend fun fetchOrder(): OrderDetailsUiState {
    val result =
        try {
          orderRepository.getOrderById(orderId)
        } catch (_: Exception) {
          currentCoroutineContext().ensureActive()
          return OrderDetailsUiState.Error.OrderFailure(OrderError.Unknown)
        }
    currentCoroutineContext().ensureActive()
    return when (result) {
      is OrderResult.Success -> fetchTrip(result.data)
      is OrderResult.Error ->
          if (result.error == OrderError.NotFound) OrderDetailsUiState.NotFound
          else OrderDetailsUiState.Error.OrderFailure(result.error)
    }
  }

  private suspend fun fetchTrip(order: Order): OrderDetailsUiState {
    val result =
        try {
          tripRepository.getTripById(order.tripId)
        } catch (_: Exception) {
          currentCoroutineContext().ensureActive()
          return OrderDetailsUiState.Error.TripFailure(TripError.Unknown)
        }
    currentCoroutineContext().ensureActive()
    return when (result) {
      is TripResult.Success -> OrderDetailsUiState.Content(order, result.data)
      is TripResult.Error -> OrderDetailsUiState.Error.TripFailure(result.error)
    }
  }
}
