package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.AuthViewModel
import com.android.sample.ui.home.HomeScreen
import com.android.sample.ui.home.HomeViewModel
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
        startDestination = if (signedIn) CommandoScreens.Home.name else CommandoScreens.Auth.name,
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
        composable(route = CommandoScreens.Home.name) {
          val homeViewModel: HomeViewModel = viewModel()
          val homeUiState by homeViewModel.uiState.collectAsState()
          HomeScreen(
              uiState = homeUiState,
              onSwitchMode = homeViewModel::switchMode,
              onProfile = {
                navController.navigate(CommandoScreens.Profile.name) { launchSingleTop = true }
              },
          )
        }
        composable(route = CommandoScreens.Profile.name) {
          ProfilePlaceholderScreen(
              onBack = { navController.popBackStack() },
              onSignOut = sessionViewModel::signOut,
              signOutError = sessionUiState.signOutError,
          )
        }
      }
    }
  }
}
