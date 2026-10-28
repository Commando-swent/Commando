package com.android.sample.data.repository

import com.android.sample.model.Order

sealed class OrderError {
  data object NotAuthenticated : OrderError()

  data object NotFound : OrderError()

  data object PermissionDenied : OrderError()

  data object InvalidData : OrderError()

  data class AlreadyDecided(val persistedOrder: Order) : OrderError()

  data object Unknown : OrderError()
}

sealed class OrderResult<out T> {
  data class Success<T>(val data: T) : OrderResult<T>()

  data class Error(val error: OrderError) : OrderResult<Nothing>()
}
