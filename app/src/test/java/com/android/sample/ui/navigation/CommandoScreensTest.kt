package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
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
  fun existingDestinationsKeepTheirRouteContracts() {
    for (destination in
        listOf(
            CommandoScreens.Auth,
            CommandoScreens.App,
            CommandoScreens.Home,
            CommandoScreens.CreateTrip,
            CommandoScreens.Profile,
            CommandoScreens.AvailableTrips,
        )) {
      assertEquals(destination.name, destination.route)
    }
    assertEquals("TripDetails/{tripId}", CommandoScreens.TripDetails.route)
    assertEquals("AddItems/{tripId}", CommandoScreens.AddItems.route)
  }

  @Test
  fun addItemsHasARequiredTripIdAndEncodesOpaqueIdsAsOneSegment() {
    assertEquals("AddItems/{tripId}", CommandoScreens.AddItems.route)
    assertEquals("AddItems/trip123", CommandoScreens.addItemsRoute("trip123"))
    for ((id, encoded) in opaqueIds) {
      assertEquals("AddItems/$encoded", CommandoScreens.addItemsRoute(id))
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
              CommandoScreens.AddItems.route,
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
      controller.navigate(CommandoScreens.addItemsRoute(id))
      val addItemsEntry = controller.currentBackStackEntry
      assertNotNull("Add Items route must match for ID: $id", addItemsEntry)
      assertEquals(CommandoScreens.AddItems.route, addItemsEntry!!.destination.route)
      assertEquals(id, addItemsEntry.arguments!!.getString(CommandoScreens.TRIP_ID_ARGUMENT))
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
