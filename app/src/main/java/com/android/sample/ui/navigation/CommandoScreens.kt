package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
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
  Profile(title = R.string.nav_profile, showBottomBar = true),
  AvailableTrips(title = R.string.trips_title, showBottomBar = true),
  TripDetails(title = R.string.trips_title),
  AddItems(title = R.string.add_items_title);

  /** Route pattern for destination registration; trip-specific destinations require a string ID. */
  val route: String
    get() =
        when (this) {
          TripDetails,
          AddItems -> "$name/{$TRIP_ID_ARGUMENT}"
          else -> name
        }

  companion object {
    const val TRIP_ID_ARGUMENT = "tripId"

    /**
     * Encodes the opaque ID as one path segment without trimming or normalizing it. Navigation
     * decodes the string argument when matching the route; consumers must not decode it again.
     */
    fun tripDetailsRoute(tripId: String): String = "${TripDetails.name}/${Uri.encode(tripId)}"

    /** Preserves the opaque trip ID for the future requester flow. */
    fun addItemsRoute(tripId: String): String = "${AddItems.name}/${Uri.encode(tripId)}"
  }
}
