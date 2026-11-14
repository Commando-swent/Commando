package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex (real-trip map integration and conflict resolution).
// Claude Code contributed the shared resume refresh, incoming orders (#147), checklist (#193)
// and the trip owner's close and complete actions (#233), and moved the shared helpers, session
// dependencies and authentication routes into their own files (#322). OpenAI Codex extracted the
// profile and catalogue routes, and Claude Code rebased them onto the shared helpers (#323).
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.credentials.exceptions.ClearCredentialException
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.android.sample.R
import com.android.sample.data.location.AndroidDeviceLocationProvider
import com.android.sample.data.location.DeviceLocationProvider
import com.android.sample.data.repository.CommandoPositionRepository
import com.android.sample.data.repository.NotificationRepository
import com.android.sample.data.repository.OrderRepository
import com.android.sample.data.repository.ProductRepository
import com.android.sample.data.repository.ProfilePhotoRepository
import com.android.sample.data.repository.RatingRepository
import com.android.sample.data.repository.RatingRepositoryFirestore
import com.android.sample.data.repository.SavedTripSearchRepository
import com.android.sample.data.repository.ShoppingChecklistRepository
import com.android.sample.data.repository.TripRepository
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.AuthRepositoryProvider
import com.android.sample.ui.auth.AndroidGoogleCredentialClient
import com.android.sample.ui.auth.AuthMode
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.home.HomeScreen
import com.android.sample.ui.home.HomeTripUiState
import com.android.sample.ui.home.HomeViewModel
import com.android.sample.ui.map.MapLocationHost
import com.android.sample.ui.map.OrderTrackingHost
import com.android.sample.ui.map.OrderTrackingViewModel
import com.android.sample.ui.map.PositionSharingCard
import com.android.sample.ui.map.PositionSharingViewModel
import com.android.sample.ui.map.TripMapScreen
import com.android.sample.ui.map.TripMapUiState
import com.android.sample.ui.map.TripMapViewModel
import com.android.sample.ui.map.rememberPositionSharingControl
import com.android.sample.ui.orders.IncomingOrdersScreen
import com.android.sample.ui.orders.IncomingOrdersViewModel
import com.android.sample.ui.orders.MyOrdersScreen
import com.android.sample.ui.orders.MyOrdersViewModel
import com.android.sample.ui.orders.OrderDetailsScreen
import com.android.sample.ui.orders.OrderDetailsUiState
import com.android.sample.ui.orders.OrderDetailsViewModel
import com.android.sample.ui.orders.OrderFlowDraft
import com.android.sample.ui.orders.ReviewOrderRoute
import com.android.sample.ui.orders.ReviewOrderViewModel
import com.android.sample.ui.orders.SessionOrderViewModel
import com.android.sample.ui.orders.ShoppingChecklistScreen
import com.android.sample.ui.orders.ShoppingChecklistViewModel
import com.android.sample.ui.profile.AppLanguage
import com.android.sample.ui.profile.ProfilePhotoSnackbarHost
import com.android.sample.ui.profile.ProfileViewModel
import com.android.sample.ui.session.SessionViewModel
import com.android.sample.ui.trips.AvailableTripsScreen
import com.android.sample.ui.trips.AvailableTripsViewModel
import com.android.sample.ui.trips.CreateTripScreen
import com.android.sample.ui.trips.CreateTripViewModel
import com.android.sample.ui.trips.MyTripsScreen
import com.android.sample.ui.trips.MyTripsViewModel
import com.android.sample.ui.trips.SavedTripSearchViewModel
import com.android.sample.ui.trips.SavedTripSearches
import com.android.sample.ui.trips.TripDetailsScreen
import com.android.sample.ui.trips.TripDetailsUiState
import com.android.sample.ui.trips.TripDetailsViewModel
import com.android.sample.ui.trips.TripLocationFilterViewModel
import com.android.sample.ui.trips.TripRatingUiState
import com.android.sample.ui.trips.TripRatingViewModel
import com.android.sample.ui.trips.formatTripDeparture
import com.commando.app.model.TripLocations
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
) {
  val context = LocalContext.current
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
              notifications = session.notifications,
              checklists = session.checklists,
              savedSearches = session.savedSearches,
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
              onLanguageSelected = onLanguageSelected,
              positions = session.positions,
              locationProvider = locationProvider,
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
    notifications: Lazy<NotificationRepository>,
    checklists: ShoppingChecklistRepository,
    savedSearches: SavedTripSearchRepository,
    userId: String,
    onSignOut: () -> Unit,
    signOutError: AuthException?,
    onLanguageSelected: (AppLanguage) -> Unit,
    positions: Lazy<CommandoPositionRepository>,
    locationProvider: DeviceLocationProvider?,
) {
  val appViewModel: AppViewModel = viewModel()
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

  val context = LocalContext.current
  val source =
      locationProvider
          ?: remember(context) { AndroidDeviceLocationProvider(context.applicationContext) }
  val sharing: PositionSharingViewModel = viewModel {
    PositionSharingViewModel(
        positions::value,
        tripRepository,
        source,
        orders::currentUserId,
        orders.sessionChanges,
    )
  }
  val (sharingState, toggleSharing) =
      rememberPositionSharingControl(sharing, enabled = appUiState.mode == AppMode.Commando)
  val signOutAfterCleanup =
      rememberSharingSignOut(sharing, appUiState.mode, onSignOut, photoSnackbarHost)
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
            sharingControl = { PositionSharingCard(sharingState, toggleSharing) },
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
            viewModel(viewModelStoreOwner = entry) {
              // The resume observer below loads on entry and on every return.
              AvailableTripsViewModel(
                  tripRepository,
                  locationFilter.selected,
                  loadImmediately = false,
                  criteria = locationFilter.criteria,
              )
            }
        RefreshOnResume(availableTripsViewModel, availableTripsViewModel::retry)
        val selectedPoint by locationFilter.selected.collectAsState()
        val criteria by locationFilter.criteria.collectAsState()
        AvailableTripsScreen(
            viewModel = availableTripsViewModel,
            selectedPoint = selectedPoint,
            points = TripLocations.handoffPoints,
            onSelectPoint = locationFilter::select,
            onClearPoint = locationFilter::clear,
            criteria = criteria,
            onCriteriaChange = locationFilter::setCriteria,
            onClearCriteria = locationFilter::clearCriteria,
            presets = {
              val presetState by savedSearchViewModel.uiState.collectAsState()
              SavedTripSearches(
                  state = presetState,
                  onSave = { savedSearchViewModel.save(it, criteria) },
                  // A preset replaces the criteria only; the delivery point is kept.
                  onApply = { locationFilter.setCriteria(it.criteria) },
                  onRename = savedSearchViewModel::rename,
                  onDelete = savedSearchViewModel::delete,
              )
            },
            onTripSelected = { tripId ->
              navController.navigate(CommandoScreens.tripDetailsRoute(tripId))
            },
            onOpenMap = {
              navController.navigate(CommandoScreens.TripMap.name) { launchSingleTop = true }
            },
        )
      }
      composable(route = CommandoScreens.MyTrips.name) { entry ->
        // The authenticated graph is keyed by account, so this list never outlives its owner.
        val myTripsViewModel: MyTripsViewModel =
            viewModel(viewModelStoreOwner = entry) { MyTripsViewModel(tripRepository, userId) }
        // Coming back from Trip Details shows a status the owner just changed there.
        RefreshOnResume(myTripsViewModel, myTripsViewModel::retry)
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
        val tripState by tripDetailsViewModel.uiState.collectAsState()
        val ownerId = (tripState as? TripDetailsUiState.Content)?.trip?.ownerId
        val (ratingState, onRetryRating) =
            rememberTripRating(ownerId, entry, orders, ratingRepositoryFactory)
        // The entry point is for the owner only; the repository and rules check ownership again.
        val ownsTrip = (tripState as? TripDetailsUiState.Content)?.trip?.ownerId == userId
        TripDetailsScreen(
            viewModel = tripDetailsViewModel,
            onBack = { navController.popBackStack() },
            onAddItems =
                if (appUiState.mode == AppMode.Requester) {
                  { selectedTripId ->
                    navController.navigate(CommandoScreens.catalogueRoute(selectedTripId))
                  }
                } else null,
            onViewOrders =
                if (appUiState.mode == AppMode.Commando && ownsTrip) {
                  { selectedTripId ->
                    navController.navigate(CommandoScreens.incomingOrdersRoute(selectedTripId)) {
                      launchSingleTop = true
                    }
                  }
                } else null,
            showOwnerActions = appUiState.mode == AppMode.Commando && ownsTrip,
            ratingState = ratingState,
            onRetryRating = onRetryRating,
            commandoName = repository.currentUser?.takeIf { it.uid == ownerId }?.displayName,
        )
      }
      composable(
          route = CommandoScreens.IncomingOrders.route,
          arguments =
              listOf(navArgument(CommandoScreens.TRIP_ID_ARGUMENT) { type = NavType.StringType }),
      ) { entry ->
        val tripId =
            requireNotNull(entry.arguments?.getString(CommandoScreens.TRIP_ID_ARGUMENT)) {
              "Incoming Orders requires the ${CommandoScreens.TRIP_ID_ARGUMENT} argument"
            }
        // The resume observer loads on entry and refreshes after a decision in Order Details.
        val incomingOrdersViewModel: IncomingOrdersViewModel =
            viewModel(viewModelStoreOwner = entry) {
              IncomingOrdersViewModel(orders.repository, tripId, loadImmediately = false)
            }
        RefreshOnResume(incomingOrdersViewModel, incomingOrdersViewModel::refresh)
        IncomingOrdersScreen(
            viewModel = incomingOrdersViewModel,
            onOrderClick = { orderId ->
              navController.navigate(CommandoScreens.orderDetailsRoute(orderId)) {
                launchSingleTop = true
              }
            },
            onOpenChecklist = {
              navController.navigate(CommandoScreens.shoppingChecklistRoute(tripId)) {
                launchSingleTop = true
              }
            },
        )
      }
      composable(
          route = CommandoScreens.ShoppingChecklist.route,
          arguments =
              listOf(navArgument(CommandoScreens.TRIP_ID_ARGUMENT) { type = NavType.StringType }),
      ) { entry ->
        val tripId =
            requireNotNull(entry.arguments?.getString(CommandoScreens.TRIP_ID_ARGUMENT)) {
              "Shopping Checklist requires the ${CommandoScreens.TRIP_ID_ARGUMENT} argument"
            }
        // Reloading on resume picks up orders accepted while the checklist was in the background.
        val checklist: ShoppingChecklistViewModel =
            viewModel(viewModelStoreOwner = entry) {
              ShoppingChecklistViewModel(orders.repository, checklists, tripId)
            }
        RefreshOnResume(checklist, checklist::refresh)
        ShoppingChecklistScreen(viewModel = checklist)
      }
      orderFlowRoutes(
          navController = navController,
          orders = orders,
          tripRepository = tripRepository,
          productRepository = productRepository,
      )
      composable(
          route = CommandoScreens.ReviewOrder.route,
          arguments = orderFlowArguments(),
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
        val editId = entry.arguments?.getString(CommandoScreens.EDIT_ORDER_ARGUMENT)
        OrderFlowDraft(orders, tripRepository, tripId, editId, { navController.popBackStack() }) {
            draft ->
          // The same draft holder as the Catalogue entry; there is no second editable cart.
          val reviewOrderViewModel: ReviewOrderViewModel =
              viewModel(viewModelStoreOwner = entry) {
                ReviewOrderViewModel(orders.repository, draft, editId)
              }
          val openOrders = {
            editId?.let(orders::finishEdit)
            navController.openSavedOrder(editId)
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
      }
      composable(route = CommandoScreens.TripMap.name) { entry ->
        val map: TripMapViewModel =
            viewModel(viewModelStoreOwner = entry) {
              TripMapViewModel(
                  tripRepository,
                  locationFilter.selected,
                  orders::currentUserId,
                  orders.sessionChanges,
                  criteria = locationFilter.criteria,
              )
            }
        RefreshOnResume(map, map::refresh)
        val state by map.uiState.collectAsState()
        val selectedPoint by locationFilter.selected.collectAsState()
        MapLocationHost(state == TripMapUiState.SessionEnded) { locationState, locate ->
          TripMapScreen(
              state,
              map::refresh,
              map::selectTrip,
              map::clearSelection,
              onTripDetails = { navController.navigate(CommandoScreens.tripDetailsRoute(it)) },
              onBack = { navController.popBackStack() },
              selectedPoint = selectedPoint,
              onClearPoint = locationFilter::clear,
              locationState = locationState,
              onLocate = locate,
          )
        }
      }
      composable(route = CommandoScreens.MyOrders.name) { entry ->
        val model: MyOrdersViewModel =
            viewModel(viewModelStoreOwner = entry) {
              MyOrdersViewModel(orders.repository, tripRepository, userId, loadImmediately = false)
            }
        RefreshOnResume(model, model::retry)
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
              OrderDetailsViewModel(
                  orderId,
                  orders.repository,
                  tripRepository,
                  orders::currentUserId,
                  orders.sessionChanges,
                  ratingRepositoryFactory = ratingRepositoryFactory,
              )
            }
        val state by model.uiState.collectAsState()
        val ratingState by model.ratingState.collectAsState()
        OrderDetailsScreen(
            state = state,
            ratingState = ratingState,
            onRatingScore = model::selectScore,
            onRatingComment = model::updateComment,
            onRatingSubmit = model::submitRating,
            onRatingDismiss = model::dismissRating,
            onBack = { navController.popBackStack() },
            onRetry = model::retry,
            onDecision = model::decide,
            onMarkReadyForHandoff = model::markReadyForHandoff,
            onCancel = model::cancel,
            onConfirmReceipt = model::confirmReceipt,
            onTrack = {
              navController.navigate(CommandoScreens.orderTrackingRoute(orderId)) {
                launchSingleTop = true
              }
            },
            onEdit = {
              (model.uiState.value as? OrderDetailsUiState.Content)
                  ?.takeIf { it.canEdit }
                  ?.let {
                    navController.navigate(
                        CommandoScreens.catalogueRoute(it.order.tripId, orderId)
                    ) {
                      launchSingleTop = true
                    }
                  }
            },
        )
      }
      composable(
          route = CommandoScreens.OrderTracking.route,
          arguments =
              listOf(navArgument(CommandoScreens.ORDER_ID_ARGUMENT) { type = NavType.StringType }),
      ) { entry ->
        val orderId = requireNotNull(entry.arguments?.getString(CommandoScreens.ORDER_ID_ARGUMENT))
        val model: OrderTrackingViewModel =
            viewModel(viewModelStoreOwner = entry) {
              OrderTrackingViewModel(
                  orderId,
                  orders.repository,
                  tripRepository,
                  positions.value,
                  orders::currentUserId,
                  orders.sessionChanges,
              )
            }
        OrderTrackingHost(model, onBack = { navController.popBackStack() })
      }
      profileRoutes(
          navController = navController,
          profileViewModel = profileViewModel,
          photoSnackbarHost = photoSnackbarHost,
          notifications = notifications,
          orders = orders,
          onSignOut = signOutAfterCleanup,
          signOutError = signOutError,
          onLanguageSelected = onLanguageSelected,
      )
    }
  }
}

/** Retains owner-specific ratings in the Trip Details entry, using its authenticated session. */
@Composable
private fun rememberTripRating(
    ownerId: String?,
    entry: NavBackStackEntry,
    orders: SessionOrderViewModel,
    ratingRepositoryFactory: (() -> String?) -> RatingRepository,
): Pair<TripRatingUiState?, () -> Unit> {
  if (ownerId == null) return null to {}
  return key(ownerId) {
    val model: TripRatingViewModel =
        viewModel(viewModelStoreOwner = entry, key = "trip-rating/$ownerId") {
          TripRatingViewModel(ratingRepositoryFactory(orders::currentUserId))
        }
    val state by model.uiState.collectAsState()
    RefreshOnResume(model to ownerId) { model.refresh(ownerId) }
    state to model::retry
  }
}

/** Stops sharing on role exit and returns a sign-out action that awaits confirmed cleanup. */
@Composable
private fun rememberSharingSignOut(
    sharing: PositionSharingViewModel,
    mode: AppMode,
    onSignOut: () -> Unit,
    snackbar: SnackbarHostState,
): () -> Unit {
  val sharingScope = rememberCoroutineScope()
  val stopError = stringResource(R.string.position_sharing_stop)
  val signOutAfterCleanup = {
    sharingScope.launch {
      if (sharing.stop().await()) onSignOut() else snackbar.showSnackbar(stopError)
    }
    Unit
  }
  LaunchedEffect(mode) {
    if (mode == AppMode.Requester && !sharing.stop().await()) snackbar.showSnackbar(stopError)
  }
  return signOutAfterCleanup
}
