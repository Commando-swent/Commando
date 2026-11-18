package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex (#323).
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.android.sample.data.repository.ProductRepository
import com.android.sample.data.repository.ProductRepositoryFirestore
import com.android.sample.data.repository.TripRepository
import com.android.sample.ui.catalogue.CatalogueRoute
import com.android.sample.ui.catalogue.CatalogueViewModel
import com.android.sample.ui.orders.OrderFlowDraft
import com.android.sample.ui.orders.SessionOrderViewModel

/** Registers the Catalogue entry while keeping its draft in the authenticated order session. */
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
        requireNotNull(entry.arguments?.getString(CommandoScreens.TRIP_ID_ARGUMENT)) {
          "Catalogue requires the ${CommandoScreens.TRIP_ID_ARGUMENT} argument"
        }
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
}
