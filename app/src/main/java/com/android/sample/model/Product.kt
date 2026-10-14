package com.android.sample.model

// AI assistance: OpenAI Codex.
data class Product(
    val id: String,
    val storeId: String,
    val name: String,
    val priceCents: Int,
    val imageUrl: String? = null,
)
