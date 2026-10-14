package com.android.sample.data.repository

sealed class ProductError {
  data object InvalidStore : ProductError()

  data object InvalidLimit : ProductError()

  data object NetworkError : ProductError()

  data object PermissionDenied : ProductError()

  data object InvalidData : ProductError()

  data object Unknown : ProductError()
}

sealed class ProductResult<out T> {
  data class Success<T>(val data: T) : ProductResult<T>()

  data class Error(val error: ProductError) : ProductResult<Nothing>()
}
