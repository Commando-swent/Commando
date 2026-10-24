package com.android.sample.ui.navigation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.credentials.exceptions.ClearCredentialException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.android.sample.R
import com.android.sample.data.repository.OrderRepository
import com.android.sample.data.repository.OrderRepositoryFirestore
import com.android.sample.data.repository.ProductRepository
import com.android.sample.data.repository.ProductRepositoryFirestore
import com.android.sample.data.repository.ProfilePhotoRepository
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripRepositoryFirestore
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.AuthRepositoryProvider
import com.android.sample.ui.auth.AndroidGoogleCredentialClient
import com.android.sample.ui.auth.AuthMode
import com.android.sample.ui.auth.AuthRoute
import com.android.sample.ui.auth.AuthViewModel
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.auth.GoogleSignInNotConfiguredException
import com.android.sample.ui.catalogue.CatalogueRoute
import com.android.sample.ui.catalogue.CatalogueViewModel
import com.android.sample.ui.home.HomeScreen
import com.android.sample.ui.home.HomeTripUiState
import com.android.sample.ui.home.HomeViewModel
import com.android.sample.ui.map.MockMapScreen
import com.android.sample.ui.map.MockMapViewModel
import com.android.sample.ui.orders.MyOrdersScreen
import com.android.sample.ui.orders.MyOrdersViewModel
import com.android.sample.ui.orders.OrderDetailsScreen
import com.android.sample.ui.orders.OrderDetailsViewModel
import com.android.sample.ui.orders.ReviewOrderRoute
import com.android.sample.ui.orders.ReviewOrderViewModel
import com.android.sample.ui.orders.SessionOrderViewModel
import com.android.sample.ui.profile.EditProfileScreen
import com.android.sample.ui.profile.ProfileHelpScreen
import com.android.sample.ui.profile.ProfilePhotoActions
import com.android.sample.ui.profile.ProfilePhotoSnackbarHost
import com.android.sample.ui.profile.ProfileScreen
import com.android.sample.ui.profile.ProfileUiState
import com.android.sample.ui.profile.ProfileViewModel
import com.android.sample.ui.session.SessionViewModel
import com.android.sample.ui.trips.AvailableTripsScreen
import com.android.sample.ui.trips.AvailableTripsViewModel
import com.android.sample.ui.trips.CreateTripScreen
import com.android.sample.ui.trips.CreateTripViewModel
import com.android.sample.ui.trips.MyTripsScreen
import com.android.sample.ui.trips.MyTripsViewModel
import com.android.sample.ui.trips.TripDetailsScreen
import com.android.sample.ui.trips.TripDetailsUiState
import com.android.sample.ui.trips.TripDetailsViewModel
import com.android.sample.ui.trips.formatTripDeparture
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
) {
  val context = LocalContext.current
  val activity = LocalActivity.current
  val googleUnavailable = stringResource(R.string.auth_google_unavailable)
  val clearSessionError = stringResource(R.string.auth_google_clear_error)
  val googleClient =
      googleCredentials
          ?: remember(context) { AndroidGoogleCredentialClient(context.applicationContext) }
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
                onBack = { activity?.finish() },
            )
          }
        }
      } else {
        composable(route = CommandoScreens.App.name) {
          val tripRepositoryViewModel: SessionTripRepositoryViewModel = viewModel {
            SessionTripRepositoryViewModel(
                tripRepository ?: TripRepositoryFirestore(authRepository = repository)
            )
          }
          val trips = tripRepositoryViewModel.repository
          val orders: SessionOrderViewModel = viewModel {
            SessionOrderViewModel(user.uid, repository) { currentUserId ->
              orderRepositoryFactory?.invoke(currentUserId)
                  ?: OrderRepositoryFirestore(currentUserId)
            }
          }
          AuthenticatedApp(
              repository = repository,
              photoRepository = photoRepository,
              tripRepository = trips,
              productRepository = productRepository,
              orders = orders,
              userId = user.uid,
              onSignOut = {
                if (sessionViewModel.signOut()) {
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
    userId: String,
    onSignOut: () -> Unit,
    signOutError: AuthException?,
) {
  val appViewModel: AppViewModel = viewModel()
  val profileViewModel: ProfileViewModel = viewModel {
    ProfileViewModel(repository, photoRepository)
  }
  val appUiState by appViewModel.uiState.collectAsState()
  val photoSnackbarHost = remember { SnackbarHostState() }
  val navController = rememberNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentScreen =
      CommandoScreens.entries.firstOrNull { it.route == backStackEntry?.destination?.route }
          ?: CommandoScreens.Home

  AppScaffold(
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
          val lifecycleOwner = LocalLifecycleOwner.current
          DisposableEffect(lifecycleOwner, homeViewModel) {
            val observer = LifecycleEventObserver { _, event ->
              if (event == Lifecycle.Event.ON_RESUME) homeViewModel.refresh()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
          }
        }
        HomeScreen(
            mode = appUiState.mode,
            tripState = tripState,
            onRetry = onRetry,
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
      composable(route = CommandoScreens.CreateTrip.name) { entry ->
        val createTripViewModel: CreateTripViewModel =
            viewModel(viewModelStoreOwner = entry) { CreateTripViewModel(tripRepository) }
        CreateTripScreen(
            viewModel = createTripViewModel,
            onBack = { navController.popBackStack() },
            onTripPublished = {
              // Resuming Home refreshes its current trip from the shared repository.
              navController.popBackStack(CommandoScreens.Home.name, inclusive = false)
            },
        )
      }
      composable(route = CommandoScreens.AvailableTrips.name) { entry ->
        val availableTripsViewModel: AvailableTripsViewModel =
            viewModel(viewModelStoreOwner = entry) { AvailableTripsViewModel(tripRepository) }
        AvailableTripsScreen(
            viewModel = availableTripsViewModel,
            onTripSelected = { tripId ->
              navController.navigate(CommandoScreens.tripDetailsRoute(tripId))
            },
            onOpenMap = {
              navController.navigate(CommandoScreens.MockMap.name) { launchSingleTop = true }
            },
        )
      }
      composable(route = CommandoScreens.MyTrips.name) { entry ->
        // The authenticated graph is keyed by account, so this list never outlives its owner.
        val myTripsViewModel: MyTripsViewModel =
            viewModel(viewModelStoreOwner = entry) { MyTripsViewModel(tripRepository, userId) }
        MyTripsScreen(
            viewModel = myTripsViewModel,
            onTripSelected = { tripId ->
              navController.navigate(CommandoScreens.tripDetailsRoute(tripId))
            },
            onPublishTrip = {
              navController.navigate(CommandoScreens.CreateTrip.name) { launchSingleTop = true }
            },
        )
      }
      composable(
          route = CommandoScreens.TripDetails.route,
          arguments =
              listOf(navArgument(CommandoScreens.TRIP_ID_ARGUMENT) { type = NavType.StringType }),
      ) { entry ->
        val tripId =
            requireNotNull(entry.arguments?.getString(CommandoScreens.TRIP_ID_ARGUMENT)) {
              "Trip Details requires the ${CommandoScreens.TRIP_ID_ARGUMENT} argument"
            }
        val tripDetailsViewModel: TripDetailsViewModel =
            viewModel(viewModelStoreOwner = entry) { TripDetailsViewModel(tripRepository, tripId) }
        TripDetailsScreen(
            viewModel = tripDetailsViewModel,
            onBack = { navController.popBackStack() },
            onAddItems =
                if (appUiState.mode == AppMode.Requester) {
                  { selectedTripId ->
                    navController.navigate(CommandoScreens.catalogueRoute(selectedTripId))
                  }
                } else null,
        )
      }
      composable(
          route = CommandoScreens.Catalogue.route,
          arguments =
              listOf(navArgument(CommandoScreens.TRIP_ID_ARGUMENT) { type = NavType.StringType }),
      ) { entry ->
        val tripId =
            requireNotNull(entry.arguments?.getString(CommandoScreens.TRIP_ID_ARGUMENT)) {
              "Catalogue requires the ${CommandoScreens.TRIP_ID_ARGUMENT} argument"
            }
        // This entry stays on the back stack behind Review, so returning keeps the selection.
        val catalogueViewModel: CatalogueViewModel =
            viewModel(viewModelStoreOwner = entry) {
              CatalogueViewModel(
                  tripId,
                  tripRepository,
                  productRepository ?: ProductRepositoryFirestore(),
                  orders.draftFor(tripId),
              )
            }
        CatalogueRoute(
            viewModel = catalogueViewModel,
            onBack = { navController.popBackStack() },
            onContinue = { trip ->
              navController.navigate(CommandoScreens.reviewOrderRoute(trip.id)) {
                launchSingleTop = true
              }
            },
        )
      }
      composable(
          route = CommandoScreens.ReviewOrder.route,
          arguments =
              listOf(navArgument(CommandoScreens.TRIP_ID_ARGUMENT) { type = NavType.StringType }),
      ) { entry ->
        val tripId =
            requireNotNull(entry.arguments?.getString(CommandoScreens.TRIP_ID_ARGUMENT)) {
              "Review Order requires the ${CommandoScreens.TRIP_ID_ARGUMENT} argument"
            }
        // Review renders the whole Trip, so the destination reloads it from its own ID rather
        // than carrying a Trip that activity recreation would lose.
        val tripViewModel: TripDetailsViewModel =
            viewModel(viewModelStoreOwner = entry) { TripDetailsViewModel(tripRepository, tripId) }
        val tripState by tripViewModel.uiState.collectAsState()
        // The same draft holder as the Catalogue entry; there is no second editable cart.
        val reviewOrderViewModel: ReviewOrderViewModel =
            viewModel(viewModelStoreOwner = entry) {
              ReviewOrderViewModel(orders.repository, orders.draftFor(tripId))
            }
        val openOrders = {
          navController.navigate(CommandoScreens.MyOrders.name) {
            popUpTo(CommandoScreens.Home.name)
            launchSingleTop = true
          }
        }
        val locale = LocalConfiguration.current.locales[0]
        when (val state = tripState) {
          // Before submission, Back and Add Products return to the retained Catalogue.
          is TripDetailsUiState.Content ->
              ReviewOrderRoute(
                  viewModel = reviewOrderViewModel,
                  trip = state.trip,
                  onBack = {
                    if (reviewOrderViewModel.uiState.value.order != null) openOrders()
                    else navController.popBackStack()
                  },
                  onAddProducts = { navController.popBackStack() },
                  // Both exits from confirmation discard the submitted draft's screens.
                  onViewOrders = { openOrders() },
                  tripSubtitle = formatTripDeparture(state.trip.scheduledAt, locale),
                  isDemo = orders.isDemo,
              )
          // Reuse Trip Details for the loading and failure states of the same lookup.
          else ->
              TripDetailsScreen(
                  state = state,
                  onRetry = tripViewModel::retry,
                  onBack = { navController.popBackStack() },
                  onAddItems = null,
              )
        }
      }
      composable(route = CommandoScreens.MockMap.name) {
        val map: MockMapViewModel = viewModel { MockMapViewModel() }
        // These marker IDs are UI fixtures, not keys in the production TripRepository.
        MockMapScreen(
            viewModel = map,
            onTripDetails = null,
            onBack = { navController.popBackStack() },
        )
      }
      composable(route = CommandoScreens.MyOrders.name) { entry ->
        val model: MyOrdersViewModel =
            viewModel(viewModelStoreOwner = entry) {
              MyOrdersViewModel(orders.repository, tripRepository, userId, loadImmediately = false)
            }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, model) {
          val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.retry()
          }
          lifecycleOwner.lifecycle.addObserver(observer)
          onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        MyOrdersScreen(
            viewModel = model,
            requesterId = userId,
            onOrderClick = { orderId ->
              navController.navigate(CommandoScreens.orderDetailsRoute(orderId)) {
                launchSingleTop = true
              }
            },
            onBrowseTrips = {
              appViewModel.switchMode(AppMode.Requester)
              navController.openScreen(CommandoScreens.AvailableTrips)
            },
            isDemo = orders.isDemo,
        )
      }
      composable(
          route = CommandoScreens.OrderDetails.route,
          arguments =
              listOf(navArgument(CommandoScreens.ORDER_ID_ARGUMENT) { type = NavType.StringType }),
      ) { entry ->
        val orderId = requireNotNull(entry.arguments?.getString(CommandoScreens.ORDER_ID_ARGUMENT))
        val model: OrderDetailsViewModel =
            viewModel(viewModelStoreOwner = entry) {
              OrderDetailsViewModel(orderId, orders.repository, tripRepository)
            }
        val state by model.uiState.collectAsState()
        OrderDetailsScreen(
            state = state,
            onBack = { navController.popBackStack() },
            onRetry = model::retry,
        )
      }
      composable(route = CommandoScreens.Profile.name) {
        val profileUiState by profileViewModel.uiState.collectAsState()
        val (photo, onPhotoClick) = ProfilePhotoActions(profileViewModel, photoSnackbarHost)
        ProfileScreen(
            uiState = profileUiState,
            onBack = { navController.popBackStack() },
            onSignOut = onSignOut,
            signOutError = signOutError,
            photo = photo,
            onPhotoClick = onPhotoClick,
            onOrderHistory = {
              // History is a child of Profile even when My Orders was opened earlier from a tab.
              navController.navigate(CommandoScreens.MyOrders.name) { launchSingleTop = true }
            },
            onHelp = { navController.openScreen(CommandoScreens.ProfileHelp) },
            onEdit = {
              profileViewModel.beginEditing()
              navController.navigate(CommandoScreens.EditProfile.name) { launchSingleTop = true }
            },
        )
      }
      composable(route = CommandoScreens.ProfileHelp.name) {
        ProfileHelpScreen(onBack = { navController.popBackStack() })
      }
      composable(route = CommandoScreens.EditProfile.name) {
        val draft by profileViewModel.editState.collectAsState()
        val profile by profileViewModel.uiState.collectAsState()
        LaunchedEffect(profile, draft == null) {
          if (profile is ProfileUiState.Content) profileViewModel.beginEditing()
        }
        val onCancel = {
          if (profileViewModel.editState.value?.isSaving != true) {
            profileViewModel.cancelEditing()
            navController.popBackStack()
          }
          Unit
        }
        BackHandler { onCancel() }
        val (photo, onPhotoClick) = ProfilePhotoActions(profileViewModel, photoSnackbarHost)
        draft?.let {
          EditProfileScreen(
              it,
              profileViewModel::updateFullName,
              profileViewModel::updateEmail,
              profileViewModel::saveProfile,
              onCancel,
              (profile as? ProfileUiState.Content)?.fullName,
              photo = photo,
              onPhotoClick = onPhotoClick,
          )
        } ?: androidx.compose.material3.CircularProgressIndicator()
      }
    }
  }
}

/** Retains one trip repository for the authenticated App entry, including activity recreation. */
private class SessionTripRepositoryViewModel(val repository: TripRepository) : ViewModel()

/** Reuse an existing top-level destination rather than stacking copies when switching tabs. */
private fun NavHostController.openScreen(screen: CommandoScreens) {
  if (!popBackStack(screen.name, inclusive = false)) {
    navigate(screen.name) { launchSingleTop = true }
  }
}
