package com.android.sample.model

// AI assistance: OpenAI Codex.
data class Product(
    val id: String,
    val storeId: String,
    val name: String,
    val priceCents: Int,
    val imageUrl: String? = null,
    /** Pack size as the store writes it ("100g", "6 x 1,5 l"); null when unknown. */
    val sizeText: String? = null,
    /** First taxonomy segment (Migros department, Coop leaf category); null when unknown. */
    val category: String? = null,
    /** Full source taxonomy path (for example "Food > Dairy > Milk"); null when unknown. */
    val categoryPath: String? = null,
)

/** Current favourite products plus saved references that are no longer available in the store. */
data class FavouriteProducts(
    val products: List<Product>,
    val unavailableProductIds: List<String>,
)

/**
 * One bounded page of products. [nextCursor] is null once a page comes back short, so the results
 * are exhausted; after a full page it is set even if the next page turns out to be empty.
 */
data class ProductPage(
    val products: List<Product>,
    val nextCursor: String?,
)
