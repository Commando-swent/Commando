package com.commando.app.ui.navigation

// AI assistance: Claude Code moved the order destinations out of CommandoApp (#378).
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.commando.app.data.repository.PublicProfileRepository
import com.commando.app.data.repository.RatingRepository
import com.commando.app.data.repository.ShoppingChecklistRepository
import com.commando.app.data.repository.TripRepository
import com.commando.app.ui.orders.IncomingOrdersScreen
import com.commando.app.ui.orders.IncomingOrdersViewModel
import com.commando.app.ui.orders.MyOrdersScreen
import com.commando.app.ui.orders.MyOrdersViewModel
import com.commando.app.ui.orders.OrderDetailsScreen
import com.commando.app.ui.orders.OrderDetailsUiState
import com.commando.app.ui.orders.OrderDetailsViewModel
import com.commando.app.ui.orders.SessionOrderViewModel
import com.commando.app.ui.orders.ShoppingChecklistScreen
import com.commando.app.ui.orders.ShoppingChecklistViewModel

internal const val ORDER_REFRESH_AFTER_CHAT = "refreshOrderAfterChat"

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
    publicProfiles: Lazy<PublicProfileRepository>? = null,
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
    val orderId =
        requireRouteArgument(entry.arguments, CommandoScreens.ORDER_ID_ARGUMENT, "Order Details")
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
    RefreshOrderAfterChat(entry, model)
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
        onChat = {
          navController.navigate(CommandoScreens.orderChatRoute(orderId)) { launchSingleTop = true }
        },
        onTrack = {
          navController.navigate(CommandoScreens.orderTrackingRoute(orderId)) {
            launchSingleTop = true
          }
        },
        onEdit = { navController.openOrderEdit(model.uiState.value, orderId) },
    )
  }
  composable(
      route = CommandoScreens.IncomingOrders.route,
      arguments =
          listOf(navArgument(CommandoScreens.TRIP_ID_ARGUMENT) { type = NavType.StringType }),
  ) { entry ->
    val tripId =
        requireRouteArgument(entry.arguments, CommandoScreens.TRIP_ID_ARGUMENT, "Incoming Orders")
    // The resume observer loads on entry and refreshes after a decision in Order Details.
    val incomingOrdersViewModel: IncomingOrdersViewModel =
        viewModel(viewModelStoreOwner = entry) {
          IncomingOrdersViewModel(
              orders.repository,
              tripId,
              loadImmediately = false,
              publicProfiles = publicProfiles,
              currentUserId = orders::currentUserId,
              sessionChanges = orders.sessionChanges,
          )
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
        requireRouteArgument(
            entry.arguments,
            CommandoScreens.TRIP_ID_ARGUMENT,
            "Shopping Checklist",
        )
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

/**
 * Opens Catalogue to edit [orderId] when the latest [state] still allows it. The button is only
 * shown for an editable order, but the order may change (for example, accepted) before the tap.
 */
internal fun NavHostController.openOrderEdit(state: OrderDetailsUiState, orderId: String) {
  val content = state as? OrderDetailsUiState.Content ?: return
  if (!content.canEdit) return
  navigate(CommandoScreens.catalogueRoute(content.order.tripId, orderId)) { launchSingleTop = true }
}

/** Returns the [name] argument of [screen]'s route, with a clear message if it is missing. */
internal fun requireRouteArgument(arguments: Bundle?, name: String, screen: String): String =
    requireNotNull(arguments?.getString(name)) { "$screen requires the $name argument" }

/** Consumes the chat return signal without reloading unrelated order-detail entries. */
@Composable
private fun RefreshOrderAfterChat(entry: NavBackStackEntry, model: OrderDetailsViewModel) {
  val refreshAfterChat by
      entry.savedStateHandle.getStateFlow(ORDER_REFRESH_AFTER_CHAT, false).collectAsState()
  LaunchedEffect(refreshAfterChat) {
    if (refreshAfterChat) {
      entry.savedStateHandle[ORDER_REFRESH_AFTER_CHAT] = false
      model.retry()
    }
  }
}
