package com.commando.app.ui.navigation

// AI assistance: OpenAI Codex (real-trip map integration and conflict resolution); Claude Code
// (connectivity monitor wiring, #393, and into trip publication, #429; session cache closed on
// sign-out, #428).
// Claude Code contributed the shared resume refresh, incoming orders (#147), checklist (#193)
// and the trip owner's close and complete actions (#233), and moved the shared helpers, session
// dependencies, authentication, trip and map routes into their own files (#322). OpenAI Codex
// extracted the profile and catalogue routes, and Claude Code rebased them onto the shared
// helpers (#323), then moved the order destinations into their own file (#378) and Review
// Order into the order-flow routes (#379).
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.credentials.exceptions.ClearCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.commando.app.R
import com.commando.app.data.connectivity.AndroidConnectivityMonitor
import com.commando.app.data.connectivity.ConnectivityMonitor
import com.commando.app.data.location.DeviceLocationProvider
import com.commando.app.data.repository.CommandoPositionRepository
import com.commando.app.data.repository.NotificationRepository
import com.commando.app.data.repository.OrderChatRepository
import com.commando.app.data.repository.OrderChatRepositoryFirestore
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.ProductRepository
import com.commando.app.data.repository.ProfilePhotoRepository
import com.commando.app.data.repository.RatingRepository
import com.commando.app.data.repository.RatingRepositoryFirestore
import com.commando.app.data.repository.SavedTripSearchRepository
import com.commando.app.data.repository.ShoppingChecklistRepository
import com.commando.app.data.repository.TripRepository
import com.commando.app.model.authentication.AuthException
import com.commando.app.model.authentication.AuthRepository
import com.commando.app.model.authentication.AuthRepositoryProvider
import com.commando.app.ui.auth.AndroidGoogleCredentialClient
import com.commando.app.ui.auth.AuthMode
import com.commando.app.ui.auth.GoogleCredentialClient
import com.commando.app.ui.chat.OrderChatHost
import com.commando.app.ui.chat.OrderChatViewModel
import com.commando.app.ui.home.HomeScreen
import com.commando.app.ui.home.HomeTripUiState
import com.commando.app.ui.home.HomeViewModel
import com.commando.app.ui.map.PositionSharingCard
import com.commando.app.ui.orders.SessionOrderViewModel
import com.commando.app.ui.profile.AppLanguage
import com.commando.app.ui.profile.ProfilePhotoSnackbarHost
import com.commando.app.ui.profile.ProfileViewModel
import com.commando.app.ui.session.SessionViewModel
import com.commando.app.ui.trips.SavedTripSearchViewModel
import com.commando.app.ui.trips.TripLocationFilterViewModel
import kotlinx.coroutines.launch

/** The form and session share the same repository; tests can inject an in-memory implementation. */
@Composable
fun CommandoApp(
    repository: AuthRepository = AuthRepositoryProvider.repository,
    tripRepository: TripRepository? = null,
    googleCredentials: GoogleCredentialClient? = null,
    photoRepository: ProfilePhotoRepository? = null,
    productRepository: ProductRepository? = null,
    orderRepositoryFactory: ((() -> String?) -> OrderRepository)? = null,
    checklistRepositoryFactory: ((() -> String?) -> ShoppingChecklistRepository)? = null,
    savedSearchRepositoryFactory: ((() -> String?) -> SavedTripSearchRepository)? = null,
    onLanguageSelected: (AppLanguage) -> Unit = {},
    positionRepositoryFactory: ((() -> String?) -> CommandoPositionRepository)? = null,
    locationProvider: DeviceLocationProvider? = null,
    notificationRepositoryFactory: ((() -> String?) -> NotificationRepository)? = null,
    ratingRepositoryFactory: ((() -> String?) -> RatingRepository)? = null,
    chatRepositoryFactory: ((() -> String?) -> OrderChatRepository)? = null,
    connectivityMonitor: ConnectivityMonitor? = null,
) {
  val context = LocalContext.current
  val clearSessionError = stringResource(R.string.auth_google_clear_error)
  val googleClient =
      googleCredentials
          ?: remember(context) { AndroidGoogleCredentialClient(context.applicationContext) }
  val scope = rememberCoroutineScope()
  val sessionViewModel: SessionViewModel = viewModel { SessionViewModel(repository) }
  val sessionUiState by sessionViewModel.uiState.collectAsState()
  // One monitor for the activity, kept across rotations and account changes (#393).
  val connectivity =
      connectivityMonitor ?: viewModel { ConnectivityViewModel(context.applicationContext) }.monitor

  // Each account gets its own controller and back stack. Signing out removes the whole
  // authenticated graph, rather than leaving protected destinations behind the login screen.
  key(sessionUiState.user?.uid) {
    val navController = rememberNavController()
    val user = sessionUiState.user
    val signedIn = user != null
    var initialAuthMode by rememberSaveable { mutableStateOf(AuthMode.LOGIN) }
    NavHost(
        navController = navController,
        startDestination = if (signedIn) CommandoScreens.App.name else CommandoScreens.Welcome.name,
    ) {
      if (!signedIn) {
        authRoutes(
            navController = navController,
            repository = repository,
            googleClient = googleClient,
            initialAuthMode = { initialAuthMode },
            onAuthModeSelected = { initialAuthMode = it },
        )
      } else {
        composable(route = CommandoScreens.App.name) {
          val session =
              rememberSessionDependencies(
                  userId = user.uid,
                  authRepository = repository,
                  tripRepository = tripRepository,
                  orderRepositoryFactory = orderRepositoryFactory,
                  checklistRepositoryFactory = checklistRepositoryFactory,
                  savedSearchRepositoryFactory = savedSearchRepositoryFactory,
                  notificationRepositoryFactory = notificationRepositoryFactory,
                  positionRepositoryFactory = positionRepositoryFactory,
              )
          AuthenticatedApp(
              repository = repository,
              photoRepository = photoRepository,
              tripRepository = session.trips,
              productRepository = productRepository,
              orders = session.orders,
              ratingRepositoryFactory =
                  ratingRepositoryFactory ?: { identity -> RatingRepositoryFirestore(identity) },
              chatRepositoryFactory = chatRepositoryFactory,
              notifications = session.notifications,
              checklists = session.checklists,
              savedSearches = session.savedSearches,
              userId = user.uid,
              onSignOut = {
                if (signOutClosing(session.cache, sessionViewModel::signOut)) {
                  scope.launch {
                    try {
                      googleClient.clearSession()
                    } catch (_: ClearCredentialException) {
                      Toast.makeText(context, clearSessionError, Toast.LENGTH_LONG).show()
                    }
                  }
                }
              },
              signOutError = sessionUiState.signOutError,
              onLanguageSelected = onLanguageSelected,
              positions = session.positions,
              locationProvider = locationProvider,
              connectivity = connectivity,
          )
        }
      }
    }
  }
}

/** The outer App entry owns the shared mode and is discarded when the session changes. */
@Composable
private fun AuthenticatedApp(
    repository: AuthRepository,
    photoRepository: ProfilePhotoRepository?,
    tripRepository: TripRepository,
    productRepository: ProductRepository?,
    orders: SessionOrderViewModel,
    ratingRepositoryFactory: (() -> String?) -> RatingRepository,
    chatRepositoryFactory: ((() -> String?) -> OrderChatRepository)?,
    notifications: Lazy<NotificationRepository>,
    checklists: ShoppingChecklistRepository,
    savedSearches: SavedTripSearchRepository,
    userId: String,
    onSignOut: () -> Unit,
    signOutError: AuthException?,
    onLanguageSelected: (AppLanguage) -> Unit,
    positions: Lazy<CommandoPositionRepository>,
    locationProvider: DeviceLocationProvider?,
    connectivity: ConnectivityMonitor,
) {
  val appViewModel: AppViewModel = viewModel()
  // Routes pass this monitor to the ViewModels that need it; it says nothing about the server.
  // Stops collecting in the background, so the monitor can release its system callback.
  val isOnline by connectivity.isOnline.collectAsStateWithLifecycle()
  // Shared by the trip list and map; this entry is keyed by account, so sign-out clears it.
  val locationFilter: TripLocationFilterViewModel = viewModel()
  val savedSearchViewModel: SavedTripSearchViewModel = viewModel {
    SavedTripSearchViewModel(savedSearches)
  }
  val profileViewModel: ProfileViewModel = viewModel {
    ProfileViewModel(repository, photoRepository)
  }
  val appUiState by appViewModel.uiState.collectAsState()
  val photoSnackbarHost = remember { SnackbarHostState() }
  val navController = rememberNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val editingOrderId = backStackEntry?.arguments?.getString(CommandoScreens.EDIT_ORDER_ARGUMENT)
  DiscardAbandonedEdit(orders, editingOrderId)
  val currentScreen =
      CommandoScreens.entries.firstOrNull { it.route == backStackEntry?.destination?.route }
          ?: CommandoScreens.Home

  val sharing =
      rememberPositionSharing(
          positions,
          tripRepository,
          orders,
          locationProvider,
          appUiState.mode,
          onSignOut,
          photoSnackbarHost,
      )
  AppScaffold(
      isOnline = isOnline,
      currentScreen = currentScreen,
      mode = appUiState.mode,
      onSwitchMode = { mode ->
        if (currentScreen == CommandoScreens.Profile && mode != appUiState.mode) {
          // Back must not restore a trips list belonging to the previous mode.
          navController.navigate(CommandoScreens.Profile.name) {
            popUpTo(CommandoScreens.Home.name)
            launchSingleTop = true
          }
        }
        if (currentScreen == CommandoScreens.TripMap && mode == AppMode.Commando) {
          navController.navigate(CommandoScreens.MyTrips.name) {
            popUpTo(CommandoScreens.Home.name)
            launchSingleTop = true
          }
        }
        appViewModel.switchMode(mode)
      },
      onHome = {
        navController.navigate(CommandoScreens.Home.name) {
          popUpTo(CommandoScreens.Home.name)
          launchSingleTop = true
        }
      },
      onBack = { navController.popBackStack() },
      snackbarHost = { ProfilePhotoSnackbarHost(photoSnackbarHost) },
      onTrips = {
        navController.openScreen(
            if (appUiState.mode == AppMode.Commando) CommandoScreens.MyTrips
            else CommandoScreens.AvailableTrips
        )
      },
      onOrders =
          if (appUiState.mode == AppMode.Requester) {
            { navController.openScreen(CommandoScreens.MyOrders) }
          } else null,
      onProfile = { navController.openScreen(CommandoScreens.Profile) },
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
          RefreshOnResume(homeViewModel, homeViewModel::refresh)
        }
        HomeScreen(
            mode = appUiState.mode,
            tripState = tripState,
            onRetry = onRetry,
            sharingControl = {
              PositionSharingCard(
                  sharing.state,
                  sharing.toggle,
                  discovery = sharing.discovery,
                  onDiscoveryToggle = sharing.toggleDiscovery,
              )
            },
            onFindTrip = { navController.navigate(CommandoScreens.AvailableTrips.name) },
            onManageTrip = { tripId ->
              navController.navigate(CommandoScreens.tripDetailsRoute(tripId)) {
                launchSingleTop = true
              }
            },
            onPublishTrip = {
              navController.navigate(CommandoScreens.CreateTrip.name) { launchSingleTop = true }
            },
        )
      }
      tripRoutes(
          navController = navController,
          tripRepository = tripRepository,
          session =
              TripRouteSession(
                  userId = userId,
                  mode = { appUiState.mode },
                  ratingRepository = { ratingRepositoryFactory(orders::currentUserId) },
                  commandoName = { ownerId ->
                    repository.currentUser?.takeIf { it.uid == ownerId }?.displayName
                  },
                  orderRepository = orders.repository,
              ),
          locationFilter = locationFilter,
          savedSearchViewModel = savedSearchViewModel,
          isOnline = connectivity.isOnline,
      )
      ordersRoutes(
          navController = navController,
          orders = orders,
          tripRepository = tripRepository,
          checklists = checklists,
          userId = userId,
          ratingRepositoryFactory = ratingRepositoryFactory,
          onBrowseTrips = {
            appViewModel.switchMode(AppMode.Requester)
            navController.openScreen(CommandoScreens.AvailableTrips)
          },
      )
      orderFlowRoutes(
          navController = navController,
          orders = orders,
          tripRepository = tripRepository,
          productRepository = productRepository,
      )
      mapRoutes(
          navController = navController,
          tripRepository = tripRepository,
          orders = orders,
          positions = positions,
          locationFilter = locationFilter,
          userId = userId,
      )
      composable(
          route = CommandoScreens.OrderChat.route,
          arguments =
              listOf(navArgument(CommandoScreens.ORDER_ID_ARGUMENT) { type = NavType.StringType }),
      ) { entry ->
        val orderId = requireNotNull(entry.arguments?.getString(CommandoScreens.ORDER_ID_ARGUMENT))
        val model: OrderChatViewModel =
            viewModel(viewModelStoreOwner = entry) {
              OrderChatViewModel(
                  orderId,
                  orders.repository,
                  tripRepository,
                  chatRepositoryFactory?.invoke(orders::currentUserId)
                      ?: OrderChatRepositoryFirestore(orders::currentUserId),
                  orders::currentUserId,
                  orders.sessionChanges,
                  entry.savedStateHandle,
              )
            }
        OrderChatHost(
            model,
            onBack = {
              navController.previousBackStackEntry
                  ?.savedStateHandle
                  ?.set(ORDER_REFRESH_AFTER_CHAT, true)
              navController.popBackStack()
            },
        )
      }

      profileRoutes(
          navController = navController,
          profileViewModel = profileViewModel,
          photoSnackbarHost = photoSnackbarHost,
          notifications = notifications,
          orders = orders,
          actions =
              ProfileRouteActions(
                  signOut = sharing.signOut,
                  signOutError = signOutError,
                  selectLanguage = onLanguageSelected,
              ),
      )
    }
  }
}

/** Owns the activity's connectivity monitor; its scope stops the callback when cleared. */
private class ConnectivityViewModel(context: Context) : ViewModel() {
  val monitor = AndroidConnectivityMonitor(context, viewModelScope)
}
