package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.AuthViewModel
import com.android.sample.ui.home.HomeScreen
import com.android.sample.ui.session.SessionViewModel

// One temporary instance for the process, shared across activity recreation as well.
private val temporaryAuthRepository: AuthRepository by lazy { FakeAuthRepository() }

/**
 * Application navigation root. The in-memory repository is temporary until the Firebase
 * implementation is available; it starts signed out and does not fabricate a successful login.
 * Inject a stable repository instance to share authentication between the form and the session.
 */
@Composable
fun CommandoApp(repository: AuthRepository = temporaryAuthRepository) {
  val sessionViewModel: SessionViewModel = viewModel { SessionViewModel(repository) }
  val sessionUiState by sessionViewModel.uiState.collectAsState()

  // Each account gets its own controller and back stack. Signing out removes the whole
  // authenticated graph, rather than leaving protected destinations behind the login screen.
  key(sessionUiState.user?.uid) {
    val navController = rememberNavController()
    val signedIn = sessionUiState.user != null
    NavHost(
        navController = navController,
        startDestination = if (signedIn) CommandoScreens.App.name else CommandoScreens.Auth.name,
    ) {
      if (!signedIn) {
        composable(route = CommandoScreens.Auth.name) {
          val authViewModel: AuthViewModel = viewModel { AuthViewModel(repository) }
          val authUiState by authViewModel.uiState.collectAsState()
          AuthPlaceholderScreen(
              mode = authUiState.mode,
              onSwitchMode = authViewModel::switchMode,
          )
        }
      } else {
        composable(route = CommandoScreens.App.name) {
          AuthenticatedApp(
              onSignOut = sessionViewModel::signOut,
              signOutError = sessionUiState.signOutError,
          )
        }
      }
    }
  }
}

/** The outer App entry owns the shared mode and is discarded when the session changes. */
@Composable
private fun AuthenticatedApp(onSignOut: () -> Unit, signOutError: AuthException?) {
  val appViewModel: AppViewModel = viewModel()
  val appUiState by appViewModel.uiState.collectAsState()
  val navController = rememberNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentScreen =
      CommandoScreens.entries.firstOrNull { it.name == backStackEntry?.destination?.route }
          ?: CommandoScreens.Home

  AppScaffold(
      currentScreen = currentScreen,
      mode = appUiState.mode,
      onSwitchMode = appViewModel::switchMode,
      onHome = {
        navController.navigate(CommandoScreens.Home.name) {
          popUpTo(CommandoScreens.Home.name)
          launchSingleTop = true
        }
      },
      onProfile = {
        navController.navigate(CommandoScreens.Profile.name) { launchSingleTop = true }
      },
  ) { padding ->
    NavHost(
        navController = navController,
        startDestination = CommandoScreens.Home.name,
        modifier = Modifier.padding(padding),
    ) {
      composable(route = CommandoScreens.Home.name) { HomeScreen(mode = appUiState.mode) }
      composable(route = CommandoScreens.Profile.name) {
        ProfilePlaceholderScreen(
            onBack = { navController.popBackStack() },
            onSignOut = onSignOut,
            signOutError = signOutError,
        )
      }
    }
  }
}
