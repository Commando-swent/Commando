package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import android.Manifest
import android.app.Application
import android.location.Location as AndroidLocation
import android.location.LocationManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.android.sample.ui.theme.SampleAppTheme
import com.commando.app.data.repository.*
import com.commando.app.data.repository.CommandoPositionRepository
import com.commando.app.data.repository.FakeCommandoPositionRepository
import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.model.*
import com.commando.app.model.authentication.AuthUser
import com.commando.app.model.authentication.FakeAuthRepository
import com.commando.app.ui.catalogue.StubProductRepository
import com.commando.app.ui.home.HomeTestTags
import com.commando.app.ui.map.*
import com.commando.app.ui.trips.AvailableTripsScreenTestTags
import com.commando.app.ui.trips.RecordingRatings
import java.time.Instant
import kotlinx.coroutines.flow.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Checks location, shared filtering and actual Trip Details routing through the real app graph. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp-mdpi")
class NearbyTripNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val ratingIds = mutableListOf<String>()

  @Test
  fun locatePreservesFilterAndOpensTheNearbyTripDetails() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
    val manager = app.getSystemService(LocationManager::class.java)
    shadowOf(manager).setLocationEnabled(true)
    shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
    val point = TripLocations.handoffPoints.first { it.name == "CM building" }
    val fix =
        AndroidLocation(LocationManager.NETWORK_PROVIDER).apply {
          latitude = point.latitude
          longitude = point.longitude
          accuracy = 150f
          elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
          time = System.currentTimeMillis()
        }
    shadowOf(manager).simulateLocation(LocationManager.NETWORK_PROVIDER, fix)
    val now = Instant.now()
    val near =
        Trip(
            "near / % 雪",
            "owner",
            Location("Nearby store", 46.52, 6.566),
            now.plusSeconds(3600),
            point,
            TripStatus.PUBLISHED,
            now,
            now,
        )
    val far =
        near.copy(
            id = "far",
            store = near.store.copy(name = "Distant store"),
            handoffLocation = Location("Distant handoff", 46.54, 6.59),
        )
    val trips = FakeTripRepository("requester", now = { now }, initialTrips = listOf(near, far))
    compose.setContent {
      SampleAppTheme {
        CommandoApp(
            FakeAuthRepository(AuthUser("requester")),
            trips,
            productRepository = StubProductRepository(),
            positionRepositoryFactory = {
              object :
                  CommandoPositionRepository by FakeCommandoPositionRepository(
                      MutableStateFlow("requester")
                  ) {
                override fun observeDiscovery(ownerId: String, tripId: String) =
                    flowOf(CommandoPosition(point.latitude, point.longitude, 20.0, now))
              }
            },
            ratingRepositoryFactory = { RecordingRatings(it, ratingIds) },
            orderRepositoryFactory = { FakeOrderRepository(it, tripRepository = trips) },
        )
      }
    }
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).performClick()
    compose.onNodeWithTag(AvailableTripsScreenTestTags.LOCATION_FIELD).performClick()
    compose.onNodeWithText(point.name).performClick()
    compose.onNodeWithTag(NavigationTestTags.OPEN_MAP).performScrollTo().performClick()
    compose.onNodeWithTag(TripMapTestTags.LOCATE).performClick()
    compose.onNodeWithTag(MapLocationTestTags.POSITION).assertIsDisplayed()
    compose
        .onNodeWithTag(TripMapTestTags.CLEAR_FILTER)
        .assert(hasText(point.name, substring = true))
    compose.onNodeWithTag(LiveMapTags.TRIP + far.id).assertDoesNotExist()
    compose.onNodeWithTag(LiveMapTags.MARKER + near.ownerId).performClick()
    compose.onNodeWithTag(LiveMapTags.TRIP + near.id).performScrollTo().performClick()
    compose.onNodeWithText(near.store.name).assertIsDisplayed()
    compose.runOnIdle { assertEquals(listOf(near.ownerId), ratingIds) }
    compose.onNodeWithTag(TripMapTestTags.HEADER).assertDoesNotExist()
    compose.onNodeWithTag(MapLocationTestTags.POSITION).assertDoesNotExist()
  }
}
