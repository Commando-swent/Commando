package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.annotation.StringRes
import com.android.sample.R

/** Navigation destinations; Auth contains the login and sign-up form modes. */
enum class CommandoScreens(
    @StringRes val title: Int,
    val showModeSelector: Boolean = false,
    val showBottomBar: Boolean = false,
) {
  Auth(title = R.string.nav_login),
  App(title = R.string.nav_home),
  Home(title = R.string.nav_home, showModeSelector = true, showBottomBar = true),
  Profile(title = R.string.nav_profile, showBottomBar = true),
}
