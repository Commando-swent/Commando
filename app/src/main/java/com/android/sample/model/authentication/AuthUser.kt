package com.android.sample.model.authentication

/** Application user information, independent of the authentication provider. */
data class AuthUser(
    val uid: String,
    val displayName: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
)
