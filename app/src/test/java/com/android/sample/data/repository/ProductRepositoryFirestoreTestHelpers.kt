package com.android.sample.data.repository

internal fun <T> ProductResult<T>.successData(): T =
    when (this) {
      is ProductResult.Success -> data
      is ProductResult.Error -> error("Expected success, got $error")
    }

/** Mirrors the importer's searchPrefixes: name and last category segment words, 2 to 15 chars. */
internal fun productSearchPrefixes(name: String, categoryPath: String = ""): Map<String, Boolean> =
    normalizeProductSearchText("$name ${categoryPath.substringAfterLast('>')}")
        .split(Regex("[^a-z0-9]+"))
        .flatMap { word -> (2..minOf(word.length, 15)).map(word::take) }
        .associateWith { true }
