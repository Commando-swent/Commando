package com.android.sample.ui.navigation

// AI assistance: Claude Code (characterization tests for session-scoped dependencies, #322).
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.credentials.Credential
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.android.sample.R
import com.android.sample.data.repository.*
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.notifications.NotificationTestTags
import com.android.sample.ui.orders.MyOrdersTestTags
import com.android.sample.ui.orders.OrderDetailsTestTags
import com.android.sample.ui.orders.SessionOrderViewModel
import com.android.sample.ui.profile.ProfileEditTestTags
import com.android.sample.ui.profile.ProfileTestTags
import com.android.sample.ui.profile.ProfileViewModel
import com.android.sample.ui.theme.SampleAppTheme
import com.commando.app.data.repository.FakeCommandoPositionRepository
import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.data.repository.FakeShoppingChecklistRepository
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.data.repository.NotificationRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.PublicProfileRepository
import com.commando.app.data.repository.SavedTripSearchRepositoryLocal
import com.commando.app.data.repository.context
import com.commando.app.model.*
import com.commando.app.ui.map.OrderTrackingTestTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records every session identity a factory receives, so tests can query each one later. */
private class Recorder<T>(private val create: (() -> String?) -> T) : (() -> String?) -> T {
  val identities = mutableListOf<() -> String?>()

  override fun invoke(identity: () -> String?): T = create(identity).also { identities += identity }

  fun uids() = identities.map { it() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h917dp-mdpi")
class SessionDependenciesTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val alice = AuthUser("alice", displayName = "Alice", email = "alice@example.com")
  private val auth = FakeAuthRepository(alice)
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val time = Instant.parse("2030-01-01T12:00:00Z")
  private val epfl = Location("EPFL", 46.5, 6.5)
  private val trip = Trip("trip", "commando", epfl, time, epfl, TripStatus.IN_PROGRESS, time, time)
  private val milk = OrderItem("milk", "Milk", 220, 2)
  private val order = Order("order", trip.id, "alice", listOf(milk), 150, OrderStatus.PENDING, time)
  private val trips = FakeTripRepository("alice", { time.minusSeconds(60) }, listOf(trip))
  private val orders = Recorder { id -> FakeOrderRepository(id, listOf(order)) }
  private val checklists = Recorder(::FakeShoppingChecklistRepository)
  private val savedSearches = Recorder { SavedTripSearchRepositoryLocal(context, it) }
  private val notifications = Recorder { _ -> EmptyNotifications }
  private val positions = Recorder { _ ->
    FakeCommandoPositionRepository(MutableStateFlow<String?>("alice"))
  }
  private lateinit var content: @Composable () -> Unit

  private fun show(publicProfiles: ((() -> String?) -> PublicProfileRepository)? = null) {
    content = {
      SampleAppTheme {
        CommandoApp(
            auth,
            trips,
            NoCredentials,
            orderRepositoryFactory = orders,
            checklistRepositoryFactory = checklists,
            savedSearchRepositoryFactory = savedSearches,
            positionRepositoryFactory = positions,
            notificationRepositoryFactory = notifications,
            publicProfileRepositoryFactory = publicProfiles,
        )
      }
    }
    compose.setContent(content)
  }

  private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

  private fun scrollClick(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

  private fun managePublicName() =
      compose
          .onNodeWithText(context.getString(R.string.public_name_manage))
          .performScrollTo()
          .performClick()

  private fun back() = compose.runOnIdle {
    compose.activity.onBackPressedDispatcher.onBackPressed()
  }

  /** Opens both notification destinations from Profile and returns to Profile. */
  private fun visitNotifications() {
    click(AppTestTags.PROFILE_BUTTON)
    scrollClick(NotificationTestTags.INBOX)
    compose.onNodeWithTag(NotificationTestTags.BACK).assertIsDisplayed()
    back()
    scrollClick(NotificationTestTags.PREFERENCES)
    compose.onNodeWithTag(NotificationTestTags.NEW_ORDERS).assertIsDisplayed()
    back()
  }

  private fun assertUids(expected: List<String?>, vararg recorders: Recorder<*>) =
      compose.runOnIdle {
        recorders.forEach { assertEquals(expected, it.uids()) }
      }

  @Test
  fun managePublicNameReadsTheSignedInUidOnceAcrossRecomposition() {
    val response = CompletableDeferred<String?>()
    val profiles = mockk<PublicProfileRepository>()
    val requestedOwners = mutableListOf<String>()
    coEvery { profiles.getDisplayName(any()) } coAnswers
        {
          requestedOwners += firstArg<String>()
          response.await()
        }
    val factory = Recorder { _ -> profiles }
    show(factory)
    click(AppTestTags.PROFILE_BUTTON)
    click(ProfileTestTags.EDIT)
    compose.runOnIdle { assertEquals(emptyList<String>(), requestedOwners) }

    managePublicName()
    assertUids(listOf(alice.uid), factory)
    compose.runOnIdle { assertEquals(listOf(alice.uid), requestedOwners) }
    val privateName = compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME)
    privateName.performScrollTo().performTextReplacement("Private draft")
    privateName.assertTextEquals("Private draft")
    compose.runOnIdle {
      assertEquals(listOf(alice.uid), requestedOwners)
      response.complete("Public alias")
    }
    compose.onNodeWithTag(ProfileEditTestTags.PUBLIC_NAME).assertTextEquals("Public alias")
  }

  @Test
  fun disposedPublicNameEditorIgnoresItsLateReadAfterReopening() {
    val oldRead = CompletableDeferred<String?>()
    val profiles = mockk<PublicProfileRepository>()
    coEvery { profiles.getDisplayName(alice.uid) } coAnswers
        {
          withContext(NonCancellable) { oldRead.await() }
        }
    lateinit var nav: NavHostController
    compose.setContent {
      SampleAppTheme {
        nav = rememberNavController()
        val model: ProfileViewModel = viewModel {
          ProfileViewModel(auth, publicProfiles = lazyOf(profiles))
        }
        val orderSession: SessionOrderViewModel = viewModel {
          SessionOrderViewModel(alice.uid, auth, orders)
        }
        NavHost(nav, startDestination = CommandoScreens.Profile.name) {
          profileRoutes(
              nav,
              model,
              SnackbarHostState(),
              lazyOf(EmptyNotifications),
              orderSession,
              ProfileRouteActions({}, null, {}),
          )
        }
      }
    }
    try {
      click(ProfileTestTags.EDIT)
      managePublicName()
      compose.runOnIdle {
        coVerify(exactly = 1) { profiles.getDisplayName(alice.uid) }
        // Bypass the edit screen's Back handler so only route disposal clears the public editor.
        nav.popBackStack()
      }
      compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).assertDoesNotExist()
      coEvery { profiles.getDisplayName(alice.uid) } returns "Current"
      click(ProfileTestTags.EDIT)
      managePublicName()
      val alias = compose.onNodeWithTag(ProfileEditTestTags.PUBLIC_NAME)
      alias.assertTextEquals("Current")
      compose.runOnIdle { oldRead.complete("Outdated") }
      alias.assertTextEquals("Current")
    } finally {
      oldRead.cancel()
    }
  }

  @Test
  fun accountChangeCreatesNewRepositoriesBoundToTheNewAccountAndEndsTheOldIdentity() = runTest {
    show()
    visitNotifications()
    assertUids(listOf("alice"), orders, checklists, savedSearches, notifications)
    auth.signInWithEmailResult = Result.success(AuthUser("bob"))
    auth.signInWithEmail("", "")
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
    visitNotifications()
    // Alice's identity no longer reports her once Bob's session replaces it.
    assertUids(listOf(null, "bob"), orders, checklists, savedSearches, notifications)
  }

  @Test
  fun sameAccountKeepsRepositoriesAcrossRecompositionAndNavigation() = runTest {
    show()
    visitNotifications()
    // Same UID, new session state: the root recomposes without changing accounts.
    auth.signInWithEmailResult = Result.success(alice.copy(displayName = "Alice updated"))
    auth.signInWithEmail("", "")
    click(AppTestTags.HOME_BUTTON)
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.REQUESTER_MODE)
    click(AppTestTags.ORDERS_BUTTON)
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + order.id).assertIsDisplayed()
    visitNotifications()
    assertUids(listOf("alice"), orders, checklists, savedSearches, notifications)
  }

  @Test
  fun activityRecreationKeepsOrdersButRebuildsRememberedSessionRepositories() {
    show()
    visitNotifications()
    compose.activityRule.scenario.recreate()
    compose.activityRule.scenario.onActivity { it.setContent(content = content) }
    visitNotifications()
    // Orders live in a ViewModel and survive. Checklists, presets and the lazy notification holder
    // are remember(orders), so recreation rebuilds them; reopening notifications reads the new one.
    assertUids(listOf("alice"), orders)
    assertUids(listOf("alice", "alice"), checklists, savedSearches, notifications)
  }

  @Test
  fun notificationAndPositionRepositoriesAreCreatedOnlyWhenFirstNeeded() {
    show()
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.REQUESTER_MODE)
    click(AppTestTags.ORDERS_BUTTON)
    click(AppTestTags.PROFILE_BUTTON)
    assertUids(emptyList(), notifications, positions)
    scrollClick(NotificationTestTags.PREFERENCES)
    compose.onNodeWithTag(NotificationTestTags.NEW_ORDERS).assertIsDisplayed()
    back()
    scrollClick(NotificationTestTags.INBOX)
    compose.onNodeWithTag(NotificationTestTags.BACK).assertIsDisplayed()
    assertUids(listOf("alice"), notifications)
    assertUids(emptyList(), positions)
    back()
    click(AppTestTags.ORDERS_BUTTON)
    click(MyOrdersTestTags.ORDER_CARD_PREFIX + order.id)
    click(OrderDetailsTestTags.TRACK)
    compose.onNodeWithTag(OrderTrackingTestTags.MARKER).assertDoesNotExist()
    assertUids(listOf("alice"), positions)
  }
}

private object NoCredentials : GoogleCredentialClient {
  override suspend fun request(context: Context): Credential? = null

  override suspend fun clearSession() = Unit
}

private object EmptyNotifications : NotificationRepository {
  override suspend fun getNotifications() = OrderResult.Success(emptyList<OrderNotification>())

  override suspend fun markAsRead(notificationId: String) = OrderResult.Success(Unit)

  override suspend fun getPreferences() = OrderResult.Success(NotificationPreferences())

  override suspend fun updatePreferences(newOrders: Boolean?, orderDecisions: Boolean?) =
      OrderResult.Success(Unit)
}
