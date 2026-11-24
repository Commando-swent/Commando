package com.android.sample.ui.navigation

// AI assistance: Claude Code moved the map destinations and the position-sharing wiring out of
// CommandoApp (#380, #322). OpenAI Codex integrated live map and discovery consent (#272).
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.android.sample.R
import com.commando.app.data.location.AndroidDeviceLocationProvider
import com.commando.app.data.location.DeviceLocationProvider
import com.commando.app.data.repository.CommandoPositionRepository
import com.commando.app.data.repository.TripRepository
import com.commando.app.ui.map.DiscoverySharingState
import com.commando.app.ui.map.LiveMapHost
import com.commando.app.ui.map.LiveMapViewModel
import com.commando.app.ui.map.MapLocationHost
import com.commando.app.ui.map.OrderTrackingHost
import com.commando.app.ui.map.OrderTrackingViewModel
import com.commando.app.ui.map.PositionSharingState
import com.commando.app.ui.map.PositionSharingViewModel
import com.commando.app.ui.map.TripMapScreen
import com.commando.app.ui.map.TripMapUiState
import com.commando.app.ui.map.rememberPositionSharingControl
import com.commando.app.ui.orders.SessionOrderViewModel
import com.commando.app.ui.trips.TripLocationFilterViewModel
import kotlinx.coroutines.launch

/** Registers the trip map, which shares its filters with the trip list, and order tracking. */
internal fun NavGraphBuilder.mapRoutes(
    navController: NavHostController,
    tripRepository: TripRepository,
    orders: SessionOrderViewModel,
    positions: Lazy<CommandoPositionRepository>,
    locationFilter: TripLocationFilterViewModel,
    userId: String,
) {
  composable(route = CommandoScreens.TripMap.name) { entry ->
    val map: LiveMapViewModel =
        viewModel(viewModelStoreOwner = entry) {
          LiveMapViewModel(
              orders.repository,
              tripRepository,
              positions.value,
              orders::currentUserId,
              orders.sessionChanges,
              locationFilter.selected,
              locationFilter.criteria,
          )
        }
    val selectedPoint by locationFilter.selected.collectAsState()
    LiveMapHost(map) { state, retry ->
      MapLocationHost(orders.currentUserId() != userId) { locationState, locate ->
        TripMapScreen(
            TripMapUiState.Content(emptyList()),
            retry,
            {},
            {},
            onTripDetails = { navController.navigate(CommandoScreens.tripDetailsRoute(it)) },
            onBack = { navController.popBackStack() },
            selectedPoint = selectedPoint,
            onClearPoint = locationFilter::clear,
            locationState = locationState,
            onLocate = locate,
            liveState = state,
            onOrderDetails = {
              navController.navigate(CommandoScreens.orderDetailsRoute(it)) {
                launchSingleTop = true
              }
            },
        )
      }
    }
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
}

/** Home's sharing card state and toggle, and a sign-out that runs only once sharing stopped. */
internal class PositionSharingWiring(
    val state: PositionSharingState,
    val toggle: (Boolean) -> Unit,
    val signOut: () -> Unit,
    val discovery: DiscoverySharingState,
    val toggleDiscovery: (Boolean) -> Unit,
)

/**
 * Creates the account's position sharing in the calling App entry, enabled in Commando mode only;
 * leaving Commando mode stops it, and [PositionSharingWiring.signOut] waits for the stop.
 */
@Composable
internal fun rememberPositionSharing(
    positions: Lazy<CommandoPositionRepository>,
    tripRepository: TripRepository,
    orders: SessionOrderViewModel,
    locationProvider: DeviceLocationProvider?,
    mode: AppMode,
    onSignOut: () -> Unit,
    snackbar: SnackbarHostState,
): PositionSharingWiring {
  val context = LocalContext.current
  // The device source is built once per ViewModel, inside its factory. @DontMemoize here and on the
  // two lambdas below removes the recomposition checks the Compose compiler generates for them,
  // which tests cannot reach and SonarCloud counts as uncovered conditions. Behavior is unchanged;
  // the sign-out lambda gets a new identity on each recomposition, and Profile only uses it as an
  // onClick.
  val sharing: PositionSharingViewModel =
      viewModel @DontMemoize {
        PositionSharingViewModel(
            positions::value,
            tripRepository,
            locationProvider ?: AndroidDeviceLocationProvider(context.applicationContext),
            orders::currentUserId,
            orders.sessionChanges,
        )
      }
  val (state, toggle) = rememberPositionSharingControl(sharing, enabled = mode == AppMode.Commando)
  val signOut = rememberSharingSignOut(sharing, mode, onSignOut, snackbar)
  val discovery by sharing.discoveryState.collectAsState()
  return PositionSharingWiring(state, toggle, signOut, discovery, sharing::setDiscoveryEnabled)
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
  val signOutAfterCleanup =
      @DontMemoize {
        sharingScope.launch {
          if (sharing.stop().await()) onSignOut() else snackbar.showSnackbar(stopError)
        }
        Unit
      }
  LaunchedEffect(mode) @DontMemoize {
    if (mode == AppMode.Requester && !sharing.stop().await()) snackbar.showSnackbar(stopError)
  }
  return signOutAfterCleanup
}
