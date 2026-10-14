package com.android.sample.data.repository

import com.android.sample.model.Product

interface ProductRepository {
  /**
   * Returns products for one normalized supermarket chain.
   *
   * The Trip store-name to [storeId] mapping belongs to the catalogue integration layer. Removed
   * products are excluded by the query itself; malformed documents are filtered after the bounded
   * query, so fewer than [limit] products may be returned.
   */
  suspend fun getProducts(storeId: String, limit: Int = 50): ProductResult<List<Product>>
}
