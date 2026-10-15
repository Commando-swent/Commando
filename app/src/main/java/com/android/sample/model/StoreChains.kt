package com.android.sample.model

// AI assistance: Claude Code.
import java.util.Locale

/** Normalized supermarket chains that have a product catalogue. */
val SUPPORTED_STORE_IDS = setOf("migros", "coop", "denner", "aldi", "lidl")

private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

/**
 * Resolves a trip store name such as "Migros EPFL" to its chain ID by whole-word match. Returns
 * null when the name contains no supported chain, or more than one, so a trip never falls back to
 * another chain's catalogue.
 */
fun storeIdFor(storeName: String): String? =
    storeName
        .lowercase(Locale.ROOT)
        .split(WORD_SEPARATOR)
        .filter { it in SUPPORTED_STORE_IDS }
        .distinct()
        .singleOrNull()
