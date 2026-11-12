package com.android.sample.ui.catalogue

object CatalogueTestTags {
  const val BACK = "catalogue_back"
  const val SEARCH = "catalogue_search"
  const val CLEAR_QUERY = "catalogue_clear_query"
  const val PRODUCT_LIST = "catalogue_product_list"
  const val MESSAGE_ACTION = "catalogue_message_action"
  const val LOAD_MORE = "catalogue_load_more"
  const val PRODUCT_IMAGE = "catalogue_product_image"
  const val IMAGE_PREVIEW = "catalogue_image_preview"
  const val IMAGE_PREVIEW_CLOSE = "catalogue_image_preview_close"
  const val SUMMARY = "catalogue_summary"
  const val CONTINUE = "catalogue_continue"
  const val CATEGORY_CHIPS = "catalogue_category_chips"
  const val CATEGORY_ALL = "catalogue_category_all"

  fun category(value: String) = "catalogue_category_$value"

  fun row(productId: String) = "catalogue_row_$productId"

  fun add(productId: String) = "catalogue_add_$productId"

  fun increase(productId: String) = "catalogue_increase_$productId"

  fun quantity(productId: String) = "catalogue_quantity_$productId"
}
