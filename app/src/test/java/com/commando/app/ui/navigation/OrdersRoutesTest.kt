package com.commando.app.ui.navigation

// AI assistance: Claude Code (#378).
import android.content.Context
import android.os.Bundle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import com.commando.app.model.Location
import com.commando.app.model.Order
import com.commando.app.model.OrderItem
import com.commando.app.model.OrderStatus
import com.commando.app.model.Trip
import com.commando.app.model.TripStatus
import com.commando.app.ui.orders.OrderDetailsUiState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Covers the order-route helpers whose failure paths the navigation journeys cannot reach. */
@RunWith(RobolectricTestRunner::class)
class OrdersRoutesTest {
  private val now = Instant.parse("2026-11-18T12:00:00Z")
  private val trip =
      Trip(
          id = "trip-1",
          ownerId = "commando",
          store = Location("Migros", 46.52, 6.63),
          scheduledAt = now.plusSeconds(3600),
          handoffLocation = Location("EPFL", 46.51, 6.57),
          status = TripStatus.PUBLISHED,
          createdAt = now.minusSeconds(3600),
          updatedAt = now,
      )
  private val pendingOrder =
      Order(
          id = "order-1",
          tripId = trip.id,
          requesterId = "requester",
          items = listOf(OrderItem("apple", "Apples", 150, 2)),
          commissionCents = 100,
          status = OrderStatus.PENDING,
          createdAt = now.minusSeconds(120),
      )

  @Test
  fun editableOrderOpensCatalogueWithItsTripAndOrder() {
    val controller = orderDetailsController()

    controller.openOrderEdit(content(pendingOrder), pendingOrder.id)

    assertEquals(CommandoScreens.Catalogue.route, controller.currentDestination?.route)
    val arguments = controller.currentBackStackEntry?.arguments
    assertEquals(trip.id, arguments?.getString(CommandoScreens.TRIP_ID_ARGUMENT))
    assertEquals(pendingOrder.id, arguments?.getString(CommandoScreens.EDIT_ORDER_ARGUMENT))
  }

  @Test
  fun orderAcceptedBeforeTheTapStaysOnOrderDetails() {
    val controller = orderDetailsController()
    val accepted = pendingOrder.copy(status = OrderStatus.ACCEPTED)

    controller.openOrderEdit(content(accepted), accepted.id)

    assertEquals(CommandoScreens.OrderDetails.route, controller.currentDestination?.route)
  }

  @Test
  fun detailsWithoutLoadedOrderStayOnOrderDetails() {
    val controller = orderDetailsController()

    controller.openOrderEdit(OrderDetailsUiState.Loading, pendingOrder.id)

    assertEquals(CommandoScreens.OrderDetails.route, controller.currentDestination?.route)
  }

  @Test
  fun routeArgumentIsReturnedWhenPresent() {
    val arguments = Bundle().apply { putString(CommandoScreens.TRIP_ID_ARGUMENT, trip.id) }

    assertEquals(
        trip.id,
        requireRouteArgument(arguments, CommandoScreens.TRIP_ID_ARGUMENT, "Incoming Orders"),
    )
  }

  @Test
  fun missingRouteArgumentNamesTheScreenAndTheArgument() {
    val error =
        assertThrows(IllegalArgumentException::class.java) {
          requireRouteArgument(null, CommandoScreens.TRIP_ID_ARGUMENT, "Shopping Checklist")
        }

    assertEquals(
        "Shopping Checklist requires the ${CommandoScreens.TRIP_ID_ARGUMENT} argument",
        error.message,
    )
  }

  private fun content(order: Order) =
      OrderDetailsUiState.Content(order = order, trip = trip, viewerId = order.requesterId)

  /** A controller already on the order's details, with Catalogue as the only other destination. */
  private fun orderDetailsController(): NavHostController {
    val controller = NavHostController(ApplicationProvider.getApplicationContext<Context>())
    controller.navigatorProvider.addNavigator(ComposeNavigator())
    controller.graph =
        controller.createGraph(startDestination = CommandoScreens.MyOrders.name) {
          composable(CommandoScreens.MyOrders.name) {}
          composable(CommandoScreens.OrderDetails.route) {}
          composable(
              CommandoScreens.Catalogue.route,
              arguments =
                  listOf(
                      navArgument(CommandoScreens.EDIT_ORDER_ARGUMENT) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                      }
                  ),
          ) {}
        }
    controller.navigate(CommandoScreens.orderDetailsRoute(pendingOrder.id))
    return controller
  }
}
