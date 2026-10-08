package com.android.sample.model

// AI assistance: Claude Code.

/**
 * Temporary list of places a trip can use, so the creation form can build a valid [NewTrip] before
 * places are picked on a map. Coordinates are the OpenStreetMap positions of each place.
 */
object TripLocations {
  val stores: List<Location> =
      listOf(
          Location("Migros EPFL", 46.5229222, 6.5656983),
          Location("Denner EPFL", 46.5228102, 6.5657045),
      )

  val handoffPoints: List<Location> =
      listOf(
          Location("Rolex Learning Center", 46.5183493, 6.5683414),
          Location("EPFL metro station", 46.5221982, 6.5661540),
          Location("SwissTech Convention Center", 46.5232016, 6.5646472),
          Location("BC building", 46.5185349, 6.5619308),
          Location("CE building", 46.5204410, 6.5704970),
          Location("CM building", 46.5205116, 6.5667474),
          Location("CO building", 46.5200241, 6.5648872),
          Location("INM building", 46.5185424, 6.5631671),
          Location("SV building", 46.5201584, 6.5631048),
      )
}
