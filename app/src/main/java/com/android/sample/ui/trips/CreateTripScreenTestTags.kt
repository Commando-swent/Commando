package com.android.sample.ui.trips

// AI assistance: Claude Code.
object CreateTripScreenTestTags {
  const val BACK_BUTTON = "createTripBackButton"
  const val STORE_FIELD = "createTripStoreField"
  const val DATE_FIELD = "createTripDateField"
  const val TIME_FIELD = "createTripTimeField"
  const val HANDOFF_LOCATION_FIELD = "createTripHandoffLocationField"
  const val STORE_ERROR = "createTripStoreError"
  const val DATE_ERROR = "createTripDateError"
  const val TIME_ERROR = "createTripTimeError"
  const val HANDOFF_LOCATION_ERROR = "createTripHandoffLocationError"
  const val ERROR_BANNER = "createTripErrorBanner"
  const val PUBLISH_BUTTON = "createTripPublishButton"
  const val PUBLISH_PROGRESS = "createTripPublishProgress"

  fun locationOption(name: String) = "createTripLocationOption_$name"
}
