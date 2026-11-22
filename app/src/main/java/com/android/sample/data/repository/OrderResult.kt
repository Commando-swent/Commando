package com.android.sample.data.repository

import com.commando.app.model.Order

sealed class OrderError {
  data object NotAuthenticated : OrderError()

  data object NotFound : OrderError()

  data object PermissionDenied : OrderError()

  data object InvalidData : OrderError()

  /** Creation was rejected because the authenticated requester owns the persisted trip. */
  data object OwnTrip : OrderError()

  /**
   * An authorized operation conflicts with persisted status or revision; the order need not be
   * terminal.
   */
  data class AlreadyDecided(val persistedOrder: Order) : OrderError()

  data object Unknown : OrderError()
}

sealed class OrderResult<out T> {
  data class Success<T>(val data: T) : OrderResult<T>()

  data class Error(val error: OrderError) : OrderResult<Nothing>()
}
