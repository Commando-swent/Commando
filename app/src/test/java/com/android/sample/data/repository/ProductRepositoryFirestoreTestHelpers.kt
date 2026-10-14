package com.android.sample.data.repository

internal fun <T> ProductResult<T>.successData(): T =
    when (this) {
      is ProductResult.Success -> data
      is ProductResult.Error -> error("Expected success, got $error")
    }
