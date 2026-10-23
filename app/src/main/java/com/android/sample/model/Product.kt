package com.android.sample.model

// AI assistance: OpenAI Codex.
data class Product(
    val id: String,
    val storeId: String,
    val name: String,
    val priceCents: Int,
    val imageUrl: String? = null,
)

/**
 * One bounded page of products. [nextCursor] is null once a page comes back short, so the results
 * are exhausted; after a full page it is set even if the next page turns out to be empty.
 */
data class ProductPage(
    val products: List<Product>,
    val nextCursor: String?,
)
