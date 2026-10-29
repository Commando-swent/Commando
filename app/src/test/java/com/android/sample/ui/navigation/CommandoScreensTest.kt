package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex; incoming-orders route test (#147): Claude Code.
import android.content.Context
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import com.android.sample.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommandoScreensTest {
  private val opaqueIds =
      listOf(
          "with space" to "with%20space",
          "a/b" to "a%2Fb",
          "a%b" to "a%25b",
          "a?b" to "a%3Fb",
          "a#b" to "a%23b",
          "é雪" to "%C3%A9%E9%9B%AA",
          " trip " to "%20trip%20",
          "e\u0301" to "e%CC%81",
          "%2F" to "%252F",
      )

  @Test
  fun availableTripsHasAStableRouteWithoutArguments() {
    assertEquals("AvailableTrips", CommandoScreens.AvailableTrips.route)
    assertFalse(CommandoScreens.AvailableTrips.route.contains('{'))
  }

  @Test
  fun myTripsHasAStableRouteAndItsOwnTitle() {
    assertEquals("MyTrips", CommandoScreens.MyTrips.route)
    assertEquals(R.string.my_trips_title, CommandoScreens.MyTrips.title)
  }

  @Test
  fun tripDetailsHasExactlyOneRequiredPathArgument() {
    assertEquals("tripId", CommandoScreens.TRIP_ID_ARGUMENT)
    assertEquals("TripDetails/{tripId}", CommandoScreens.TripDetails.route)
  }

  @Test
  fun orderDetailsHasItsOwnRequiredOrderIdAndTitle() {
    assertEquals("orderId", CommandoScreens.ORDER_ID_ARGUMENT)
    assertEquals("OrderDetails/{orderId}", CommandoScreens.OrderDetails.route)
    assertEquals(R.string.order_details_title, CommandoScreens.OrderDetails.title)
  }

  @Test
  fun orderDetailsEncodesNormalAndOpaqueIdsOnceWithoutAlteration() {
    for (id in listOf("order123", "order-123_ABC", "123")) {
      assertEquals("OrderDetails/$id", CommandoScreens.orderDetailsRoute(id))
    }
    for ((id, encoded) in opaqueIds) {
      assertEquals("OrderDetails/$encoded", CommandoScreens.orderDetailsRoute(id))
    }
    assertEquals("OrderDetails/%20order", CommandoScreens.orderDetailsRoute(" order"))
    assertEquals("OrderDetails/order%20", CommandoScreens.orderDetailsRoute("order "))
  }

  @Test
  fun incomingOrdersHasARequiredTripIdItsOwnTitleAndEncodesOpaqueIds() {
    assertEquals("IncomingOrders/{tripId}", CommandoScreens.IncomingOrders.route)
    assertEquals(R.string.incoming_orders_title, CommandoScreens.IncomingOrders.title)
    assertEquals("IncomingOrders/trip%20%2F%201", CommandoScreens.incomingOrdersRoute("trip / 1"))
  }

  @Test
  fun existingDestinationsKeepTheirRouteContracts() {
    for (destination in
        listOf(
            CommandoScreens.Auth,
            CommandoScreens.App,
            CommandoScreens.Home,
            CommandoScreens.CreateTrip,
            CommandoScreens.Profile,
            CommandoScreens.AvailableTrips,
            CommandoScreens.MyTrips,
        )) {
      assertEquals(destination.name, destination.route)
    }
    assertEquals("TripDetails/{tripId}", CommandoScreens.TripDetails.route)
    assertEquals("Catalogue/{tripId}", CommandoScreens.Catalogue.route)
    assertEquals("ReviewOrder/{tripId}", CommandoScreens.ReviewOrder.route)
  }

  @Test
  fun catalogueAndReviewKeepTheirOwnTitlesAndDrawTheirOwnChrome() {
    assertEquals(R.string.add_items_title, CommandoScreens.Catalogue.title)
    assertEquals(R.string.order_review, CommandoScreens.ReviewOrder.title)
    // Both screens own their top and bottom bars, so the shared scaffold adds none.
    for (screen in listOf(CommandoScreens.Catalogue, CommandoScreens.ReviewOrder)) {
      assertFalse(screen.showBottomBar)
      assertFalse(screen.showModeSelector)
    }
  }

  @Test
  fun catalogueAndReviewHaveARequiredTripIdAndEncodeOpaqueIdsAsOneSegment() {
    assertEquals("Catalogue/trip123", CommandoScreens.catalogueRoute("trip123"))
    assertEquals("ReviewOrder/trip123", CommandoScreens.reviewOrderRoute("trip123"))
    for ((id, encoded) in opaqueIds) {
      assertEquals("Catalogue/$encoded", CommandoScreens.catalogueRoute(id))
      assertEquals("ReviewOrder/$encoded", CommandoScreens.reviewOrderRoute(id))
    }
  }

  @Test
  fun ordinaryIdsArePreserved() {
    for (id in listOf("trip123", "trip-123_ABC", "123")) {
      assertEquals("TripDetails/$id", CommandoScreens.tripDetailsRoute(id))
    }
  }

  @Test
  fun opaqueIdsAreEncodedAsOnePathSegment() {
    for ((id, encoded) in opaqueIds) {
      assertEquals("TripDetails/$encoded", CommandoScreens.tripDetailsRoute(id))
    }
  }

  @Test
  fun leadingAndTrailingSpacesAndUnicodeFormsRemainDistinct() {
    assertEquals("TripDetails/%20trip", CommandoScreens.tripDetailsRoute(" trip"))
    assertEquals("TripDetails/trip%20", CommandoScreens.tripDetailsRoute("trip "))
    assertNotEquals(
        CommandoScreens.tripDetailsRoute("trip"),
        CommandoScreens.tripDetailsRoute(" trip "),
    )
    assertNotEquals(
        CommandoScreens.tripDetailsRoute("é"),
        CommandoScreens.tripDetailsRoute("e\u0301"),
    )
  }

  @Test
  fun emptyIdProducesATrailingSlashWithoutAFallback() {
    // This faithfully constructs a route, but supplies no nonempty required path segment.
    // Whether the app should allow selecting an empty ID belongs to the navigation integration.
    assertEquals("TripDetails/", CommandoScreens.tripDetailsRoute(""))
    assertEquals("OrderDetails/", CommandoScreens.orderDetailsRoute(""))
  }

  @Test
  fun navigationRouteMatchingRecoversOpaqueIdsExactlyOnce() {
    // Exercise Navigation's matcher with a String argument, without wiring CommandoApp.
    val controller = NavHostController(ApplicationProvider.getApplicationContext<Context>())
    controller.navigatorProvider.addNavigator(ComposeNavigator())
    controller.graph =
        controller.createGraph(startDestination = CommandoScreens.AvailableTrips.route) {
          composable(CommandoScreens.AvailableTrips.route) {}
          composable(
              CommandoScreens.TripDetails.route,
              arguments =
                  listOf(
                      navArgument(CommandoScreens.TRIP_ID_ARGUMENT) { type = NavType.StringType }
                  ),
          ) {}
          composable(
              CommandoScreens.Catalogue.route,
              arguments =
                  listOf(
                      navArgument(CommandoScreens.TRIP_ID_ARGUMENT) { type = NavType.StringType }
                  ),
          ) {}
          composable(
              CommandoScreens.ReviewOrder.route,
              arguments =
                  listOf(
                      navArgument(CommandoScreens.TRIP_ID_ARGUMENT) { type = NavType.StringType }
                  ),
          ) {}
          composable(
              CommandoScreens.OrderDetails.route,
              arguments =
                  listOf(
                      navArgument(CommandoScreens.ORDER_ID_ARGUMENT) { type = NavType.StringType }
                  ),
          ) {}
        }
    assertThrows(IllegalArgumentException::class.java) {
      controller.navigate(CommandoScreens.OrderDetails.name)
    }
    for (id in opaqueIds.map { it.first } + "trip123") {
      controller.navigate(CommandoScreens.tripDetailsRoute(id))
      val entry = controller.currentBackStackEntry
      assertNotNull("Route must match for ID: $id", entry)
      assertEquals(id, entry!!.arguments!!.getString(CommandoScreens.TRIP_ID_ARGUMENT))
      controller.navigate(CommandoScreens.catalogueRoute(id))
      val catalogueEntry = controller.currentBackStackEntry
      assertNotNull("Catalogue route must match for ID: $id", catalogueEntry)
      assertEquals(CommandoScreens.Catalogue.route, catalogueEntry!!.destination.route)
      assertEquals(id, catalogueEntry.arguments!!.getString(CommandoScreens.TRIP_ID_ARGUMENT))
      controller.navigate(CommandoScreens.reviewOrderRoute(id))
      val reviewEntry = controller.currentBackStackEntry!!
      assertEquals(CommandoScreens.ReviewOrder.route, reviewEntry.destination.route)
      assertEquals(id, reviewEntry.arguments!!.getString(CommandoScreens.TRIP_ID_ARGUMENT))
      assertTrue(controller.popBackStack())
      assertTrue(controller.popBackStack())
      assertSame(entry, controller.currentBackStackEntry)
      controller.navigate(CommandoScreens.orderDetailsRoute(id))
      val orderEntry = controller.currentBackStackEntry!!
      assertEquals(CommandoScreens.OrderDetails.route, orderEntry.destination.route)
      assertEquals(id, orderEntry.arguments!!.getString(CommandoScreens.ORDER_ID_ARGUMENT))
      val argument = orderEntry.destination.arguments.getValue(CommandoScreens.ORDER_ID_ARGUMENT)
      assertEquals(NavType.StringType, argument.type)
      assertFalse(argument.isNullable)
      assertFalse(argument.isDefaultValuePresent)
      assertTrue(controller.popBackStack())
      assertSame(entry, controller.currentBackStackEntry)
    }
  }
}
