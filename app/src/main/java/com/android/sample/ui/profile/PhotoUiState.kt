package com.android.sample.ui.profile

data class PhotoUiState(
    val image: String? = null,
    val busy: Boolean = false,
    val failed: Boolean = false,
)
