package com.android.sample.ui.catalogue

object CatalogueTestTags {
  const val BACK = "catalogue_back"
  const val SEARCH = "catalogue_search"
  const val CLEAR_QUERY = "catalogue_clear_query"
  const val PRODUCT_LIST = "catalogue_product_list"
  const val MESSAGE_ACTION = "catalogue_message_action"
  const val SUMMARY = "catalogue_summary"
  const val CONTINUE = "catalogue_continue"

  fun row(productId: String) = "catalogue_row_$productId"

  fun add(productId: String) = "catalogue_add_$productId"

  fun increase(productId: String) = "catalogue_increase_$productId"

  fun quantity(productId: String) = "catalogue_quantity_$productId"
}
