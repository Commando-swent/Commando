package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.credentials.Credential
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.TripRepository
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.AuthRepositoryProvider
import com.android.sample.ui.auth.AuthMode
import com.android.sample.ui.auth.AuthRoute
import com.android.sample.ui.auth.AuthViewModel
import com.android.sample.ui.home.HomeScreen
import com.android.sample.ui.home.HomeTripUiState
import com.android.sample.ui.home.HomeViewModel
import com.android.sample.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/** Session-driven navigation; the form and session observer share one auth repository. */
@Composable
fun CommandoApp(
    repository: AuthRepository = AuthRepositoryProvider.repository,
    tripRepository: TripRepository? = null,
    requestGoogleCredential: suspend () -> Credential? = {
      throw AuthException.InvalidGoogleCredential()
    },
    clearCredentialState: suspend () -> Unit = {},
) {
  val activity = LocalActivity.current
  val scope = rememberCoroutineScope()
  val sessionViewModel: SessionViewModel = viewModel { SessionViewModel(repository) }
  val sessionUiState by sessionViewModel.uiState.collectAsState()

  // Each account gets its own controller and back stack. Signing out removes the whole
  // authenticated graph, rather than leaving protected destinations behind the login screen.
  key(sessionUiState.user?.uid) {
    val navController = rememberNavController()
    val user = sessionUiState.user
    val signedIn = user != null
    NavHost(
        navController = navController,
        startDestination = if (signedIn) CommandoScreens.App.name else CommandoScreens.Auth.name,
    ) {
      if (!signedIn) {
        composable(route = CommandoScreens.Auth.name) {
          val authViewModel: AuthViewModel = viewModel { AuthViewModel(repository) }
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
                requestGoogleCredential = requestGoogleCredential,
                // SessionViewModel observes repository updates and replaces the graph.
                onAuthenticated = {},
                onBack = {
                  if (authUiState.mode == AuthMode.SIGN_UP) authViewModel.switchMode(AuthMode.LOGIN)
                  else activity?.finish()
                },
            )
          }
        }
      } else {
        composable(route = CommandoScreens.App.name) {
          val userId = requireNotNull(user).uid
          val trips =
              remember(userId, tripRepository) {
                tripRepository ?: FakeTripRepository(currentUserId = userId)
              }
          AuthenticatedApp(
              tripRepository = trips,
              onSignOut = {
                sessionViewModel.signOut()
                if (repository.currentUser == null) {
                  scope.launch { clearCredentialState() }
                }
              },
              signOutError = sessionUiState.signOutError,
          )
        }
      }
    }
  }
}

/** The outer App entry owns the shared mode and is discarded when the session changes. */
@Composable
private fun AuthenticatedApp(
    tripRepository: TripRepository,
    onSignOut: () -> Unit,
    signOutError: AuthException?,
) {
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
      composable(route = CommandoScreens.Home.name) {
        var tripState: HomeTripUiState = HomeTripUiState.Empty
        var onRetry: () -> Unit = {}
        if (appUiState.mode == AppMode.Commando) {
          val homeViewModel: HomeViewModel = viewModel { HomeViewModel(tripRepository) }
          val state by homeViewModel.uiState.collectAsState()
          tripState = state
          onRetry = homeViewModel::refresh
          val lifecycleOwner = LocalLifecycleOwner.current
          DisposableEffect(lifecycleOwner, homeViewModel) {
            val observer = LifecycleEventObserver { _, event ->
              if (event == Lifecycle.Event.ON_RESUME) homeViewModel.refresh()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
          }
        }
        HomeScreen(mode = appUiState.mode, tripState = tripState, onRetry = onRetry)
      }
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
