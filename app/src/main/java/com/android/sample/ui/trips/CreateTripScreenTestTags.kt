package com.android.sample.ui.trips

// AI assistance: Claude Code.
object CreateTripScreenTestTags {
  const val BACK_BUTTON = "createTripBackButton"
  const val ADD_STORE_BUTTON = "createTripAddStoreButton"
  const val STORE_NAME_INPUT = "createTripStoreNameInput"
  const val STORE_DIALOG_CONFIRM = "createTripStoreDialogConfirm"
  const val DATE_FIELD = "createTripDateField"
  const val TIME_FIELD = "createTripTimeField"
  const val HANDOFF_LOCATION_INPUT = "createTripHandoffLocationInput"
  const val MAX_ORDERS_INPUT = "createTripMaxOrdersInput"
  const val STORES_ERROR = "createTripStoresError"
  const val DATE_ERROR = "createTripDateError"
  const val TIME_ERROR = "createTripTimeError"
  const val HANDOFF_LOCATION_ERROR = "createTripHandoffLocationError"
  const val MAX_ORDERS_ERROR = "createTripMaxOrdersError"
  const val ERROR_BANNER = "createTripErrorBanner"
  const val PUBLISH_BUTTON = "createTripPublishButton"
  const val PUBLISH_PROGRESS = "createTripPublishProgress"

  fun storeChip(name: String) = "createTripStoreChip_$name"

  fun removeStoreButton(name: String) = "createTripRemoveStore_$name"
}
