package com.android.sample.data.repository

sealed class TripError {
  data object NotFound : TripError()

  data object NetworkError : TripError()

  data object PermissionDenied : TripError()

  data object InvalidData : TripError()

  data object Unknown : TripError()
}

sealed class TripResult<out T> {
  data class Success<T>(val data: T) : TripResult<T>()

  data class Error(val error: TripError) : TripResult<Nothing>()
}
