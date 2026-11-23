package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex (#323). Claude Code moved Review Order and its helpers here (#379).
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.android.sample.ui.catalogue.CatalogueRoute
import com.android.sample.ui.catalogue.CatalogueViewModel
import com.android.sample.ui.orders.OrderFlowDraft
import com.android.sample.ui.orders.ReviewOrderRoute
import com.android.sample.ui.orders.ReviewOrderViewModel
import com.android.sample.ui.orders.SessionOrderViewModel
import com.commando.app.data.repository.ProductRepository
import com.commando.app.data.repository.ProductRepositoryFirestore
import com.commando.app.data.repository.TripRepository
import com.commando.app.ui.trips.TripDetailsScreen
import com.commando.app.ui.trips.TripDetailsUiState
import com.commando.app.ui.trips.TripDetailsViewModel
import com.commando.app.ui.trips.formatTripDeparture

/**
 * Registers Catalogue and Review Order. Both entries edit the same draft, kept in the authenticated
 * order session, so Back from Review returns to Catalogue with the selection intact.
 */
internal fun NavGraphBuilder.orderFlowRoutes(
    navController: NavHostController,
    orders: SessionOrderViewModel,
    tripRepository: TripRepository,
    productRepository: ProductRepository?,
) {
  composable(
      route = CommandoScreens.Catalogue.route,
      arguments = orderFlowArguments(),
  ) { entry ->
    val tripId =
        requireRouteArgument(entry.arguments, CommandoScreens.TRIP_ID_ARGUMENT, "Catalogue")
    val editId = entry.arguments?.getString(CommandoScreens.EDIT_ORDER_ARGUMENT)
    OrderFlowDraft(orders, tripRepository, tripId, editId, { navController.popBackStack() }) { draft
      ->
      // This entry stays on the back stack behind Review, so returning keeps the selection.
      val catalogueViewModel: CatalogueViewModel =
          viewModel(viewModelStoreOwner = entry) {
            CatalogueViewModel(
                tripId,
                tripRepository,
                productRepository ?: ProductRepositoryFirestore(),
                draft,
            )
          }
      CatalogueRoute(
          viewModel = catalogueViewModel,
          onBack = {
            editId?.let(orders::finishEdit)
            navController.popBackStack()
          },
          onContinue = { trip ->
            navController.navigate(CommandoScreens.reviewOrderRoute(trip.id, editId)) {
              launchSingleTop = true
            }
          },
      )
    }
  }
  composable(
      route = CommandoScreens.ReviewOrder.route,
      arguments = orderFlowArguments(),
  ) { entry ->
    val tripId =
        requireRouteArgument(entry.arguments, CommandoScreens.TRIP_ID_ARGUMENT, "Review Order")
    // Review renders the whole Trip, so the destination reloads it from its own ID rather than
    // carrying a Trip that activity recreation would lose.
    val tripViewModel: TripDetailsViewModel =
        viewModel(viewModelStoreOwner = entry) { TripDetailsViewModel(tripRepository, tripId) }
    val tripState by tripViewModel.uiState.collectAsState()
    val editId = entry.arguments?.getString(CommandoScreens.EDIT_ORDER_ARGUMENT)
    // Every exit that stays inside the flow goes back to the retained Catalogue entry.
    val popBack: () -> Unit = { navController.popBackStack() }
    OrderFlowDraft(orders, tripRepository, tripId, editId, popBack) { draft ->
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
                  if (reviewOrderViewModel.uiState.value.order != null) openOrders() else popBack()
                },
                onAddProducts = popBack,
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
                onBack = popBack,
                onAddItems = null,
            )
      }
    }
  }
}

/** Catalogue and Review carry the same trip and optional persisted edit identity. */
private fun orderFlowArguments() =
    listOf(
        navArgument(CommandoScreens.TRIP_ID_ARGUMENT) { type = NavType.StringType },
        navArgument(CommandoScreens.EDIT_ORDER_ARGUMENT) {
          type = NavType.StringType
          nullable = true
          defaultValue = null
        },
    )

/** Replaces a saved edit's details entry while preserving its caller, including My Orders. */
private fun NavHostController.openSavedOrder(editId: String?) {
  val target = editId?.let(CommandoScreens::orderDetailsRoute) ?: CommandoScreens.MyOrders.name
  navigate(target) {
    if (editId != null) popUpTo(target) { inclusive = true } else popUpTo(CommandoScreens.Home.name)
    launchSingleTop = true
  }
}
