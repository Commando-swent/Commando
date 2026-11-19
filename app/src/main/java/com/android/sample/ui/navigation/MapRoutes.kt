package com.android.sample.ui.navigation

// AI assistance: Claude Code moved the map destinations and the position-sharing wiring out of
// CommandoApp (#380, #322).
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
import com.android.sample.data.location.AndroidDeviceLocationProvider
import com.android.sample.data.location.DeviceLocationProvider
import com.android.sample.data.repository.CommandoPositionRepository
import com.android.sample.data.repository.TripRepository
import com.android.sample.ui.map.MapLocationHost
import com.android.sample.ui.map.OrderTrackingHost
import com.android.sample.ui.map.OrderTrackingViewModel
import com.android.sample.ui.map.PositionSharingState
import com.android.sample.ui.map.PositionSharingViewModel
import com.android.sample.ui.map.TripMapScreen
import com.android.sample.ui.map.TripMapUiState
import com.android.sample.ui.map.TripMapViewModel
import com.android.sample.ui.map.rememberPositionSharingControl
import com.android.sample.ui.orders.SessionOrderViewModel
import com.android.sample.ui.trips.TripLocationFilterViewModel
import kotlinx.coroutines.launch

/** Registers the trip map, which shares its filters with the trip list, and order tracking. */
internal fun NavGraphBuilder.mapRoutes(
    navController: NavHostController,
    tripRepository: TripRepository,
    orders: SessionOrderViewModel,
    positions: Lazy<CommandoPositionRepository>,
    locationFilter: TripLocationFilterViewModel,
) {
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
  return PositionSharingWiring(state, toggle, signOut)
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
