package com.android.sample.data.repository

internal fun <T> TripResult<T>.successData(): T =
    when (this) {
      is TripResult.Success -> data
      is TripResult.Error -> error("Expected success, got $error")
    }

internal fun String.parseEmulatorHostAndPort(): Pair<String, Int> {
  val separatorIndex = lastIndexOf(':')
  check(separatorIndex > 0) { "Emulator host must have the form host:port" }
  return substring(0, separatorIndex) to substring(separatorIndex + 1).toInt()
}
