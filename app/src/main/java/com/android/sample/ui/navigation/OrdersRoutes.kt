package com.android.sample.ui.navigation

// AI assistance: Claude Code moved the order destinations out of CommandoApp (#378).
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.android.sample.data.repository.RatingRepository
import com.android.sample.data.repository.ShoppingChecklistRepository
import com.android.sample.data.repository.TripRepository
import com.android.sample.ui.orders.IncomingOrdersScreen
import com.android.sample.ui.orders.IncomingOrdersViewModel
import com.android.sample.ui.orders.MyOrdersScreen
import com.android.sample.ui.orders.MyOrdersViewModel
import com.android.sample.ui.orders.OrderDetailsScreen
import com.android.sample.ui.orders.OrderDetailsUiState
import com.android.sample.ui.orders.OrderDetailsViewModel
import com.android.sample.ui.orders.SessionOrderViewModel
import com.android.sample.ui.orders.ShoppingChecklistScreen
import com.android.sample.ui.orders.ShoppingChecklistViewModel

/**
 * Registers My Orders, Order Details, Incoming Orders and the Shopping Checklist. Every order
 * ViewModel reads the session's [orders] repository, so signing out discards them with the session.
 * [userId] is the signed-in requester, [ratingRepositoryFactory] builds the receipt rating
 * repository for the session, and [onBrowseTrips] leaves an empty My Orders for the trips list.
 */
internal fun NavGraphBuilder.ordersRoutes(
    navController: NavHostController,
    orders: SessionOrderViewModel,
    tripRepository: TripRepository,
    checklists: ShoppingChecklistRepository,
    userId: String,
    ratingRepositoryFactory: (() -> String?) -> RatingRepository,
    onBrowseTrips: () -> Unit,
) {
  composable(route = CommandoScreens.MyOrders.name) { entry ->
    val model: MyOrdersViewModel =
        viewModel(viewModelStoreOwner = entry) {
          MyOrdersViewModel(orders.repository, tripRepository, userId, loadImmediately = false)
        }
    RefreshOnResume(model, model::retry)
    MyOrdersScreen(
        viewModel = model,
        requesterId = userId,
        onOrderClick = navController::openOrderDetails,
        onBrowseTrips = onBrowseTrips,
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
                navController.navigate(CommandoScreens.catalogueRoute(it.order.tripId, orderId)) {
                  launchSingleTop = true
                }
              }
        },
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
        onOrderClick = navController::openOrderDetails,
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
}

/** My Orders and Incoming Orders open the same details page without stacking copies of it. */
private fun NavHostController.openOrderDetails(orderId: String) {
  navigate(CommandoScreens.orderDetailsRoute(orderId)) { launchSingleTop = true }
}
