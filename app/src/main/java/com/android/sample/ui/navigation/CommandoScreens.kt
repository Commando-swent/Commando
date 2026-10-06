package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.annotation.StringRes
import com.android.sample.R

/** Navigation destinations; Auth contains the login and sign-up form modes. */
enum class CommandoScreens(@StringRes val title: Int) {
  Auth(title = R.string.nav_login),
  Home(title = R.string.nav_home),
  Profile(title = R.string.nav_profile),
}
