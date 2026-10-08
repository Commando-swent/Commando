package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.ui.auth.AuthMode

/** Temporary form destination; the actual authentication UI belongs to issue #5. */
@Composable
internal fun AuthPlaceholderScreen(mode: AuthMode, onSwitchMode: (AuthMode) -> Unit) {
  val isLogin = mode == AuthMode.LOGIN
  NavigationPlaceholder(
      title = stringResource(if (isLogin) CommandoScreens.Auth.title else R.string.nav_sign_up),
      tag = if (isLogin) NavigationTestTags.LOGIN_SCREEN else NavigationTestTags.SIGN_UP_SCREEN,
  ) {
    Button(
        onClick = { onSwitchMode(if (isLogin) AuthMode.SIGN_UP else AuthMode.LOGIN) },
        modifier = Modifier.testTag(NavigationTestTags.AUTH_MODE_BUTTON),
    ) {
      Text(stringResource(if (isLogin) R.string.nav_sign_up else CommandoScreens.Auth.title))
    }
  }
}

@Composable
private fun NavigationPlaceholder(
    title: String,
    tag: String,
    content: @Composable () -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxSize().testTag(tag).padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.testTag(NavigationTestTags.SCREEN_TITLE),
    )
    Text(
        stringResource(R.string.nav_screen_pending),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    content()
  }
}
