package com.commando.app.ui.navigation

// AI assistance: Claude Code moved the signed-out destinations out of CommandoApp (#322).
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.commando.app.R
import com.commando.app.model.authentication.AuthRepository
import com.commando.app.ui.auth.AuthMode
import com.commando.app.ui.auth.AuthRoute
import com.commando.app.ui.auth.AuthViewModel
import com.commando.app.ui.auth.GoogleCredentialClient
import com.commando.app.ui.auth.GoogleSignInNotConfiguredException
import com.commando.app.ui.auth.WelcomeScreen

/**
 * Registers Welcome and the login/sign-up form of the signed-out graph. [initialAuthMode] is read
 * when the form is created, after Welcome reported the chosen form through [onAuthModeSelected].
 */
internal fun NavGraphBuilder.authRoutes(
    navController: NavHostController,
    repository: AuthRepository,
    googleClient: GoogleCredentialClient,
    initialAuthMode: () -> AuthMode,
    onAuthModeSelected: (AuthMode) -> Unit,
) {
  composable(route = CommandoScreens.Welcome.name) {
    WelcomeScreen(
        onLogIn = {
          onAuthModeSelected(AuthMode.LOGIN)
          navController.navigate(CommandoScreens.Auth.name) { launchSingleTop = true }
        },
        onCreateAccount = {
          onAuthModeSelected(AuthMode.SIGN_UP)
          navController.navigate(CommandoScreens.Auth.name) { launchSingleTop = true }
        },
    )
  }
  composable(route = CommandoScreens.Auth.name) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val googleUnavailable = stringResource(R.string.auth_google_unavailable)
    val authViewModel: AuthViewModel = viewModel { AuthViewModel(repository, initialAuthMode()) }
    val authUiState by authViewModel.uiState.collectAsState()
    Box(
        Modifier.fillMaxSize()
            .testTag(
                if (authUiState.mode == AuthMode.LOGIN) NavigationTestTags.LOGIN_SCREEN
                else NavigationTestTags.SIGN_UP_SCREEN
            )
    ) {
      AuthRoute(
          viewModel = authViewModel,
          requestGoogleCredential = {
            try {
              googleClient.request(activity ?: context)
            } catch (_: GoogleSignInNotConfiguredException) {
              Toast.makeText(context, googleUnavailable, Toast.LENGTH_LONG).show()
              null
            }
          },
          // Session observation changes the graph after authentication succeeds.
          onAuthenticated = {},
          onBack = { navController.popBackStack() },
      )
    }
  }
}
