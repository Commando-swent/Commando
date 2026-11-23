package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.data.repository.*
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.catalogue.StubProductRepository
import com.android.sample.ui.home.HomeTestTags
import com.android.sample.ui.map.*
import com.android.sample.ui.orders.OrderDetailsTestTags
import com.android.sample.ui.theme.SampleAppTheme
import com.android.sample.ui.trips.RecordingRatings
import com.android.sample.ui.trips.discoveryTrip
import com.commando.app.data.repository.FakeCommandoPositionRepository
import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.data.repository.OrderError
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.model.*
import java.time.Instant
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises exact details destinations, automatic modes and session cleanup through the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp-mdpi")
class LiveMapNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val now = Instant.now()
  private val auth = FakeAuthRepository(AuthUser("viewer"))
  private val trip =
      discoveryTrip(
          "trip % 雪",
          TripLocations.stores.first(),
          CAMPUS_DISCOVERY_CENTER,
          now.plusSeconds(3600),
          "alice",
          now,
      )
  private val other = trip.copy(id = "other", ownerId = "bob")
  private val trips =
      FakeTripRepository(
          "viewer",
          initialTrips = listOf(trip.copy(status = TripStatus.COMPLETED), other),
      )

  private fun order(id: String) =
      Order(
          id,
          trip.id,
          "viewer",
          listOf(OrderItem("milk", "Milk", 200, 1)),
          0,
          OrderStatus.PENDING,
          now,
      )

  private val all = listOf(order("one % 雪"), order("two?#"), order("three").copy(tripId = other.id))
  private val feed = MutableStateFlow<OrderResult<List<Order>>?>(OrderResult.Success(all))
  private val lookups = mutableListOf<String>()
  private val coordinates =
      MutableStateFlow(
          mapOf(
              "alice" to CommandoPosition(46.523, 6.566, 20.0, now),
              "bob" to CommandoPosition(46.522, 6.569, 20.0, now),
          )
      )

  private fun show() {
    compose.setContent {
      SampleAppTheme {
        CommandoApp(
            auth,
            trips,
            productRepository = StubProductRepository(),
            ratingRepositoryFactory = { RecordingRatings(it, mutableListOf()) },
            positionRepositoryFactory = {
              FakeCommandoPositionRepository(
                  MutableStateFlow("viewer"),
                  positions = coordinates,
                  discoveryTrips = MutableStateFlow(mapOf("bob" to other.id)),
              )
            },
            orderRepositoryFactory = { identity ->
              val base = FakeOrderRepository(identity, initialOrders = all, tripRepository = trips)
              object : OrderRepository by base {
                override fun observeMyOrders() = feed

                override suspend fun getOrderById(orderId: String): OrderResult<Order> {
                  lookups += orderId
                  return base.getOrderById(orderId)
                }
              }
            },
        )
      }
    }
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).performClick()
    compose.onNodeWithTag(NavigationTestTags.OPEN_MAP).performScrollTo().performClick()
  }

  private fun back() = compose.runOnIdle {
    compose.activity.onBackPressedDispatcher.onBackPressed()
  }

  @Test
  fun everyGroupedOrderOpensItsExactDetailsAndLeavingCancelsTheFeed() {
    show()
    compose.onNodeWithTag(LiveMapTags.MARKER + "alice").assertExists()
    compose.onNodeWithTag(LiveMapTags.MARKER + "bob").assertExists()
    for (order in all) {
      compose.onNodeWithTag(LiveMapTags.ORDER + order.id).performScrollTo().performClick()
      compose.onNodeWithTag(OrderDetailsTestTags.TRACK).assertExists()
      compose.runOnIdle {
        assertEquals(order.id, lookups.last())
        assertEquals(0, feed.subscriptionCount.value)
      }
      back()
    }
    compose.runOnIdle { auth.signOut() }
    compose.onNodeWithTag(LiveMapTags.SUMMARY).assertDoesNotExist()
    compose.runOnIdle { assertEquals(0, feed.subscriptionCount.value) }
  }

  @Test
  fun confirmedTerminalOrdersRestoreDiscoveryWithoutShowingUnconfirmedOrFailedFeeds() {
    show()
    compose.runOnIdle { feed.value = null }
    compose.onNodeWithTag(LiveMapTags.MARKER + "alice").assertDoesNotExist()
    compose.onNodeWithTag(LiveMapTags.TRIP + other.id).assertDoesNotExist()
    compose.runOnIdle { feed.value = OrderResult.Error(OrderError.Unknown) }
    compose.onNodeWithTag(LiveMapTags.RETRY).assertExists()
    compose.runOnIdle {
      feed.value = OrderResult.Success(all.map { it.copy(status = OrderStatus.DELIVERED) })
    }
    compose.onNodeWithTag(LiveMapTags.MARKER + "alice").assertDoesNotExist()
    compose.onNodeWithTag(LiveMapTags.TRIP + other.id).performScrollTo().performClick()
    compose.onNodeWithText(other.store.name).assertExists()
    back()
    compose.runOnIdle { feed.value = OrderResult.Success(all) }
    compose.onNodeWithTag(LiveMapTags.ORDER + all.first().id).assertExists()
  }
}
