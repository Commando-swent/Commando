package com.android.sample.data.repository

import com.android.sample.model.Product
import com.android.sample.model.ProductPage

interface ProductRepository {
  /**
   * Returns products for one normalized supermarket chain.
   *
   * The Trip store-name to [storeId] mapping belongs to the catalogue integration layer. Removed
   * products are excluded by the query itself; malformed documents are filtered after the bounded
   * query, so fewer than [limit] products may be returned.
   */
  suspend fun getProducts(storeId: String, limit: Int = 50): ProductResult<List<Product>>

  /**
   * Searches the whole catalogue of one supermarket chain, one bounded page at a time.
   *
   * The [query] is normalized (case, accents, French ligatures) and split into words of at least
   * two characters; a product matches when every word is a prefix of a word in its name or in the
   * last segment of its category path. Only the first five words are used and a blank [query] lists
   * every active product. Pages are ordered by document ID: pass the returned
   * [ProductPage.nextCursor], with the same [storeId] and [query], to get the next one; it stays
   * valid if that product is removed meanwhile. As with [getProducts], malformed documents are
   * dropped, so a page may hold fewer than [limit] products even when a next page exists.
   */
  suspend fun searchProducts(
      storeId: String,
      query: String,
      cursor: String? = null,
      limit: Int = 50,
  ): ProductResult<ProductPage>
}
