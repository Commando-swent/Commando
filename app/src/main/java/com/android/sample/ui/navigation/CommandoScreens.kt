package com.android.sample.ui.navigation

// AI assistance: Claude Code for the incoming-orders (#147) and checklist (#193) destinations.
import android.net.Uri
import androidx.annotation.StringRes
import com.android.sample.R

/** Navigation destinations; Welcome opens Auth in the selected login or sign-up mode. */
enum class CommandoScreens(
    @StringRes val title: Int,
    val showModeSelector: Boolean = false,
    val showBottomBar: Boolean = false,
) {
  Welcome(title = R.string.welcome_title),
  Auth(title = R.string.nav_login),
  App(title = R.string.nav_home),
  Home(title = R.string.nav_home, showModeSelector = true, showBottomBar = true),
  CreateTrip(title = R.string.create_trip_title),
  EditProfile(title = R.string.profile_edit_title),
  Profile(title = R.string.nav_profile, showModeSelector = true, showBottomBar = true),
  ProfileHelp(title = R.string.profile_help),
  ProfileLanguage(title = R.string.profile_language),
  Notifications(title = R.string.notifications_title),
  NotificationPreferences(title = R.string.notifications_preferences),
  AvailableTrips(title = R.string.trips_title, showModeSelector = true, showBottomBar = true),
  MyTrips(title = R.string.my_trips_title, showModeSelector = true, showBottomBar = true),
  TripDetails(title = R.string.trips_title),
  TripMap(title = R.string.trip_map_title, showModeSelector = true, showBottomBar = true),
  MyOrders(title = R.string.my_orders_title, showBottomBar = true),
  OrderDetails(title = R.string.order_details_title),
  /** Orders placed on one of the owner's trips; reached from that trip's details. */
  IncomingOrders(title = R.string.incoming_orders_title),
  /** Products to buy for one of the owner's trips; reached from its incoming orders. */
  ShoppingChecklist(title = R.string.shopping_checklist_title),
  /** Product catalogue of the selected trip's chain; titled after the Trip Details action. */
  Catalogue(title = R.string.add_items_title),
  ReviewOrder(title = R.string.order_review);

  /** Route pattern for destination registration; detail destinations require a string ID. */
  val route: String
    get() =
        when (this) {
          TripDetails,
          IncomingOrders,
          ShoppingChecklist -> "$name/{$TRIP_ID_ARGUMENT}"
          Catalogue,
          ReviewOrder -> "$name/{$TRIP_ID_ARGUMENT}?$EDIT_ORDER_ARGUMENT={$EDIT_ORDER_ARGUMENT}"
          OrderDetails -> "$name/{$ORDER_ID_ARGUMENT}"
          else -> name
        }

  companion object {
    const val TRIP_ID_ARGUMENT = "tripId"
    const val ORDER_ID_ARGUMENT = "orderId"
    const val EDIT_ORDER_ARGUMENT = "editOrderId"

    /**
     * Encodes the opaque ID as one path segment without trimming or normalizing it. Navigation
     * decodes the string argument when matching the route; consumers must not decode it again.
     */
    fun tripDetailsRoute(tripId: String): String = "${TripDetails.name}/${Uri.encode(tripId)}"

    /** Opens the catalogue of the selected trip, preserving its opaque ID. */
    fun catalogueRoute(tripId: String, editOrderId: String? = null): String =
        "${Catalogue.name}/${Uri.encode(tripId)}" + editQuery(editOrderId)

    /** Reviews the shared draft of the selected trip before it is submitted. */
    fun reviewOrderRoute(tripId: String, editOrderId: String? = null): String =
        "${ReviewOrder.name}/${Uri.encode(tripId)}" + editQuery(editOrderId)

    private fun editQuery(orderId: String?) =
        orderId?.let { "?editOrderId=${Uri.encode(it)}" }.orEmpty()

    /** Opens the incoming orders of the owner's trip, preserving its opaque ID. */
    fun incomingOrdersRoute(tripId: String): String = "${IncomingOrders.name}/${Uri.encode(tripId)}"

    /** Opens the shopping checklist of the owner's trip, preserving its opaque ID. */
    fun shoppingChecklistRoute(tripId: String): String =
        "${ShoppingChecklist.name}/${Uri.encode(tripId)}"

    /** Encodes the opaque order ID once; consumers use Navigation's decoded string argument. */
    fun orderDetailsRoute(orderId: String): String = "${OrderDetails.name}/${Uri.encode(orderId)}"
  }
}
