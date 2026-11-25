package com.commando.app.ui.navigation

// AI assistance: Claude Code moved the trip destinations out of CommandoApp (#322) and added the
// owner's earnings (#301), and wired the trip form's saved, offline-aware publication (#429).
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.RatingRepository
import com.commando.app.data.repository.TripRepository
import com.commando.app.model.TripLocations
import com.commando.app.ui.trips.AvailableTripsScreen
import com.commando.app.ui.trips.AvailableTripsViewModel
import com.commando.app.ui.trips.CreateTripScreen
import com.commando.app.ui.trips.CreateTripViewModel
import com.commando.app.ui.trips.MyTripsScreen
import com.commando.app.ui.trips.MyTripsViewModel
import com.commando.app.ui.trips.SavedTripSearchViewModel
import com.commando.app.ui.trips.SavedTripSearches
import com.commando.app.ui.trips.TripDetailsScreen
import com.commando.app.ui.trips.TripDetailsUiState
import com.commando.app.ui.trips.TripDetailsViewModel
import com.commando.app.ui.trips.TripEarningsViewModel
import com.commando.app.ui.trips.TripLocationFilterViewModel
import com.commando.app.ui.trips.TripRatingUiState
import com.commando.app.ui.trips.TripRatingViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * The signed-in account as seen by the trip routes. [mode] is read when a destination composes, not
 * when the graph is built, so Trip Details shows the current mode's actions. [ratingRepository] and
 * [orderRepository] are bound to the session, and [commandoName] returns the user's name when they
 * own the trip.
 */
internal class TripRouteSession(
    val userId: String,
    val mode: () -> AppMode,
    val ratingRepository: () -> RatingRepository,
    val commandoName: (String?) -> String?,
    val orderRepository: OrderRepository,
)

/** Registers trip creation, discovery, the owner's list and Trip Details. */
internal fun NavGraphBuilder.tripRoutes(
    navController: NavHostController,
    tripRepository: TripRepository,
    session: TripRouteSession,
    locationFilter: TripLocationFilterViewModel,
    savedSearchViewModel: SavedTripSearchViewModel,
    isOnline: StateFlow<Boolean>,
) {
  composable(route = CommandoScreens.CreateTrip.name) { entry ->
    val createTripViewModel: CreateTripViewModel =
        viewModel(viewModelStoreOwner = entry) {
          CreateTripViewModel(tripRepository, createSavedStateHandle(), session.userId, isOnline)
        }
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
        viewModel(viewModelStoreOwner = entry) { MyTripsViewModel(tripRepository, session.userId) }
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
    val (ratingState, onRetryRating) = rememberTripRating(ownerId, entry, session.ratingRepository)
    // The entry point is for the owner only; the repository and rules check ownership again.
    val ownsTrip = (tripState as? TripDetailsUiState.Content)?.trip?.ownerId == session.userId
    val showOwnerActions = session.mode() == AppMode.Commando && ownsTrip
    TripDetailsScreen(
        viewModel = tripDetailsViewModel,
        onBack = { navController.popBackStack() },
        onAddItems =
            if (session.mode() == AppMode.Requester) {
              { selectedTripId ->
                navController.navigate(CommandoScreens.catalogueRoute(selectedTripId))
              }
            } else null,
        onViewOrders =
            if (showOwnerActions) {
              { selectedTripId ->
                navController.navigate(CommandoScreens.incomingOrdersRoute(selectedTripId)) {
                  launchSingleTop = true
                }
              }
            } else null,
        showOwnerActions = showOwnerActions,
        ratingState = ratingState,
        onRetryRating = onRetryRating,
        commandoName = session.commandoName(ownerId),
        earningsViewModel =
            rememberTripEarnings(showOwnerActions, tripId, entry, session.orderRepository),
    )
  }
}

/**
 * Retains the owner's earnings in the Trip Details entry and reloads them each time the owner comes
 * back. Returns null, so no order query runs, unless [show] is true for the trip's owner.
 */
@Composable
private fun rememberTripEarnings(
    show: Boolean,
    tripId: String,
    entry: NavBackStackEntry,
    orderRepository: OrderRepository,
): TripEarningsViewModel? {
  if (!show) return null
  val model: TripEarningsViewModel =
      viewModel(viewModelStoreOwner = entry, key = "trip-earnings/$tripId") {
        // The resume observer below loads on entry and on every return.
        TripEarningsViewModel(orderRepository, tripId, loadImmediately = false)
      }
  RefreshOnResume(model, model::refresh)
  return model
}

/**
 * Retains owner-specific ratings in the Trip Details entry; [ratingRepository] is bound to the
 * signed-in session by the caller.
 */
@Composable
private fun rememberTripRating(
    ownerId: String?,
    entry: NavBackStackEntry,
    ratingRepository: () -> RatingRepository,
): Pair<TripRatingUiState?, () -> Unit> {
  if (ownerId == null) return null to {}
  return key(ownerId) {
    val model: TripRatingViewModel =
        viewModel(viewModelStoreOwner = entry, key = "trip-rating/$ownerId") {
          TripRatingViewModel(ratingRepository())
        }
    val state by model.uiState.collectAsState()
    RefreshOnResume(model to ownerId) { model.refresh(ownerId) }
    state to model::retry
  }
}
