package com.android.sample.ui.navigation

import android.net.Uri
import androidx.annotation.StringRes
import com.android.sample.R

/** Navigation destinations; Auth contains the login and sign-up form modes. */
enum class CommandoScreens(
    @StringRes val title: Int,
    val showModeSelector: Boolean = false,
    val showBottomBar: Boolean = false,
) {
  Auth(title = R.string.nav_login),
  App(title = R.string.nav_home),
  Home(title = R.string.nav_home, showModeSelector = true, showBottomBar = true),
  CreateTrip(title = R.string.create_trip_title),
  EditProfile(title = R.string.profile_edit_title),
  Profile(title = R.string.nav_profile, showModeSelector = true, showBottomBar = true),
  AvailableTrips(title = R.string.trips_title, showModeSelector = true, showBottomBar = true),
  MyTrips(title = R.string.my_trips_title, showModeSelector = true, showBottomBar = true),
  TripDetails(title = R.string.trips_title),
  MockMap(title = R.string.nav_mock_map, showBottomBar = true),
  MyOrders(title = R.string.my_orders_title, showBottomBar = true),
  OrderDetails(title = R.string.order_details_title),
  /** Product catalogue of the selected trip's chain; titled after the Trip Details action. */
  Catalogue(title = R.string.add_items_title),
  ReviewOrder(title = R.string.order_review);

  /** Route pattern for destination registration; detail destinations require a string ID. */
  val route: String
    get() =
        when (this) {
          TripDetails,
          Catalogue,
          ReviewOrder -> "$name/{$TRIP_ID_ARGUMENT}"
          OrderDetails -> "$name/{$ORDER_ID_ARGUMENT}"
          else -> name
        }

  companion object {
    const val TRIP_ID_ARGUMENT = "tripId"
    const val ORDER_ID_ARGUMENT = "orderId"

    /**
     * Encodes the opaque ID as one path segment without trimming or normalizing it. Navigation
     * decodes the string argument when matching the route; consumers must not decode it again.
     */
    fun tripDetailsRoute(tripId: String): String = "${TripDetails.name}/${Uri.encode(tripId)}"

    /** Opens the catalogue of the selected trip, preserving its opaque ID. */
    fun catalogueRoute(tripId: String): String = "${Catalogue.name}/${Uri.encode(tripId)}"

    /** Reviews the shared draft of the selected trip before it is submitted. */
    fun reviewOrderRoute(tripId: String): String = "${ReviewOrder.name}/${Uri.encode(tripId)}"

    /** Encodes the opaque order ID once; consumers use Navigation's decoded string argument. */
    fun orderDetailsRoute(orderId: String): String = "${OrderDetails.name}/${Uri.encode(orderId)}"
  }
}
