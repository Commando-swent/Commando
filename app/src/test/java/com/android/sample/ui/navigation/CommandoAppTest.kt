package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex (real-trip map integration).

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.credentials.Credential
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.ClearCredentialUnknownException
import com.android.sample.R
import com.android.sample.data.repository.FakeCommandoPositionRepository
import com.android.sample.data.repository.FakeOrderRepository
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepositoryProvider
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.auth.GoogleSignInNotConfiguredException
import com.android.sample.ui.auth.WelcomeTestTags
import com.android.sample.ui.catalogue.CatalogueTestTags
import com.android.sample.ui.catalogue.StubProductRepository
import com.android.sample.ui.home.HomeTestTags
import com.android.sample.ui.map.MockMapTestTags
import com.android.sample.ui.map.TripMapTestTags
import com.android.sample.ui.profile.ProfileEditTestTags
import com.android.sample.ui.profile.ProfileTestTags
import com.android.sample.ui.theme.SampleAppTheme
import com.android.sample.ui.trips.AvailableTripsScreenTestTags
import com.android.sample.ui.trips.CreateTripScreenTestTags
import com.android.sample.ui.trips.MyTripsTestTags
import com.android.sample.ui.trips.ScriptedTripRepository
import com.android.sample.ui.trips.assertTripCard
import com.android.sample.ui.trips.discoveryTrip
import com.android.sample.ui.trips.onTripCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

private const val SEEDED_TRIP_ID = "known-trip"

@RunWith(RobolectricTestRunner::class)
class CommandoAppTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val alice = AuthUser("alice", displayName = "Alice Smith", email = "alice@example.com")
  private val bob = AuthUser("bob", displayName = "Bob Jones", email = "bob@example.com")

  @Test
  @Config(qualifiers = "en-rUS-w412dp-h830dp-mdpi")
  fun profileKeepsItsScrollPositionAfterReturningFromEveryMenuCategory() {
    show(FakeAuthRepository(alice))
    compose.onNodeWithTag(AppTestTags.ORDERS_BUTTON).performClick()
    compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).performClick()
    val scroll =
        compose.onNode(
            hasScrollAction() and hasAnyAncestor(hasTestTag(NavigationTestTags.PROFILE_SCREEN))
        )
    for (category in
        listOf(ProfileTestTags.HELP, ProfileTestTags.LANGUAGE, ProfileTestTags.ORDER_HISTORY)) {
      compose.onNodeWithTag(ProfileTestTags.HELP).performScrollTo()
      val before =
          scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
      assertTrue("The profile must be scrolled before opening a category", before > 0f)
      compose.onNodeWithTag(category).performClick()
      if (category == ProfileTestTags.ORDER_HISTORY) {
        compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).performClick()
      } else {
        compose.onNodeWithTag(ProfileTestTags.SETTINGS_BACK).performClick()
      }
      val after =
          scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
      assertEquals("Returning from $category must restore the profile position", before, after, 1f)
    }
  }

  @Test
  fun profileHistoryReusesMyOrdersAndBackReturnsToProfile() {
    show(FakeAuthRepository(alice))
    compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).performClick()
    compose.onNodeWithTag(ProfileTestTags.ORDER_HISTORY).performScrollTo().performClick()
    compose.onNodeWithText("My Orders").assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("Alice Smith")
  }

  @Test
  fun commandoCanReadHistoryAndBrowseTripsInRequesterMode() {
    show(FakeAuthRepository(alice))
    compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).performClick()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).performClick()
    compose.onNodeWithTag(ProfileTestTags.ORDER_HISTORY).performScrollTo().performClick()
    compose.onNodeWithText("My Orders").assertIsDisplayed()
    compose.onNodeWithText("Browse trips").performClick()
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsSelected()
    compose.onNodeWithText("Available runs").assertIsDisplayed()
  }

  @Test
  fun helpBackReturnsToProfileAndSignOutClearsTheHelpDestination() {
    val repository = FakeAuthRepository(alice)
    show(repository)
    compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).performClick()
    compose.onNodeWithTag(ProfileTestTags.HELP).performScrollTo().performClick()
    compose.onNodeWithTag(ProfileTestTags.HELP_SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTestTags.SETTINGS_BACK).performClick()
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("Alice Smith")
    compose.onNodeWithTag(ProfileTestTags.HELP).performScrollTo().performClick()
    compose.runOnIdle { repository.signOut() }
    compose.onNodeWithTag(com.android.sample.ui.auth.WelcomeTestTags.SCREEN).assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(ProfileTestTags.HELP_SCREEN).assertDoesNotExist()
  }

  private class Picker : GoogleCredentialClient {
    var credential: Credential? = null
    var error: Exception? = null
    var clearError: Exception? = null
    var clearCalls = 0
    var clearGate: CompletableDeferred<Unit>? = null

    override suspend fun request(context: Context): Credential? {
      error?.let { throw it }
      return credential
    }

    override suspend fun clearSession() {
      clearCalls++
      clearGate?.await()
      clearError?.let { throw it }
    }
  }

  // These fixtures use unsupported store names, so the catalogue never queries this stub.
  private val products = StubProductRepository()

  private fun show(
      repository: FakeAuthRepository,
      picker: Picker = Picker(),
      openForm: Boolean = true,
  ) {
    show(repository, FakeTripRepository(currentUserId = "alice"), picker, openForm)
  }

  private fun show(
      repository: FakeAuthRepository,
      trips: TripRepository,
      picker: Picker = Picker(),
      openForm: Boolean = true,
  ) {
    compose.setContent {
      SampleAppTheme {
        CommandoApp(
            repository,
            trips,
            picker,
            productRepository = products,
            orderRepositoryFactory = { FakeOrderRepository(it, tripRepository = trips) },
            positionRepositoryFactory = {
              FakeCommandoPositionRepository(
                  kotlinx.coroutines.flow.MutableStateFlow<String?>(it())
              )
            },
        )
      }
    }
    // Existing form tests explicitly enter through the new public welcome destination.
    if (openForm && repository.currentUser == null) {
      compose.onNodeWithTag(WelcomeTestTags.LOGIN).performScrollTo().performClick()
    }
  }

  private fun seededTrips(uid: String, storeName: String): TripRepository {
    val trip =
        discoveryTrip(
            SEEDED_TRIP_ID,
            Location(storeName, 46.52, 6.63),
            Location("Known handoff", 46.52, 6.57),
            tripTime.plusSeconds(3600),
            ownerId = "trip-owner",
            createdAt = tripTime,
        )
    return FakeTripRepository(uid, now = { tripTime }, initialTrips = listOf(trip))
  }

  private fun assertScreen(tag: String) {
    compose.onNodeWithTag(tag).assertIsDisplayed()
  }

  private fun click(tag: String) {
    compose.onNodeWithTag(tag).performClick()
  }

  private fun pressBack() {
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
  }

  private fun assertProfile(fullName: String, email: String, initials: String) {
    assertScreen(NavigationTestTags.PROFILE_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.SCREEN_TITLE).assertTextEquals("Profile")
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals(fullName)
    compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals(email)
    compose.onNodeWithTag(ProfileTestTags.INITIALS).assertTextEquals(initials)
  }

  private val tripTime = Instant.now()
  private val firstTrip =
      discoveryTrip(
          "trip-a",
          Location("First store", 46.52, 6.63),
          Location("First handoff", 46.52, 6.57),
          tripTime.plusSeconds(3600),
          ownerId = "owner-a",
          createdAt = tripTime,
      )
  private val secondTrip =
      firstTrip.copy(
          id = " /%2F ?#雪 e\u0301 é% trip / ",
          store = Location("Second store", 46.53, 6.64),
          scheduledAt = tripTime.plusSeconds(7200),
          handoffLocation = Location("Second handoff", 46.53, 6.58),
      )

  private fun twoTrips(uid: String): TripRepository =
      FakeTripRepository(uid, now = { tripTime }, initialTrips = listOf(firstTrip, secondTrip))

  private fun assertTripDetails(trip: Trip) {
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.BOTTOM_BAR).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.BACK_BUTTON).assertIsDisplayed()
    compose.onNodeWithText("Back").assertIsDisplayed()
    compose.onNodeWithText(trip.store.name).assertIsDisplayed()
    val expectedTime =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(compose.activity.resources.configuration.locales[0])
            .withZone(ZoneId.systemDefault())
            .format(trip.scheduledAt)
    compose.onNodeWithText(expectedTime).assertIsDisplayed()
    compose.onNodeWithText(trip.handoffLocation.name).assertIsDisplayed()
  }

  private fun assertTripScaffoldHidden() {
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.BOTTOM_BAR).assertDoesNotExist()
  }

  private fun assertCatalogue() {
    compose.onNodeWithText("Add items").assertIsDisplayed().assertTextEquals("Add items")
    compose.onNodeWithTag(CatalogueTestTags.BACK).assertIsDisplayed()
    // An unsupported chain has nothing to select, so the trip cannot be continued either.
    compose.onNodeWithTag(CatalogueTestTags.CONTINUE).assertIsDisplayed().assertIsNotEnabled()
    compose.onNodeWithText("Add items to this run").assertDoesNotExist()
    assertTripScaffoldHidden()
  }

  private fun assertAvailableTripsRestored() {
    compose.onNodeWithText("Back").assertDoesNotExist()
    compose.assertTripCard(firstTrip)
    compose.assertTripCard(secondTrip)
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertIsDisplayed()
    assertScreen(AppTestTags.BOTTOM_BAR)
  }

  @Test
  fun selectedOpaqueTripIsLoadedExactlyAndBothBackActionsRestoreTheExistingList() {
    val trips = ScriptedTripRepository(twoTrips(alice.uid))
    val requestedIds = trips.requestedIds
    show(FakeAuthRepository(alice), trips)
    click(HomeTestTags.TRIP_ACTION)
    assertAvailableTripsRestored()
    compose.onTripCard(secondTrip.id).performClick()
    assertTripDetails(secondTrip)
    compose.onNodeWithText(firstTrip.store.name).assertDoesNotExist()
    compose.onNodeWithText(firstTrip.handoffLocation.name).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.BOTTOM_BAR).assertDoesNotExist()
    compose
        .onNodeWithText("Add items to this run")
        .performScrollTo()
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    assertCatalogue()
    click(CatalogueTestTags.BACK)
    assertTripDetails(secondTrip)
    compose.onNodeWithText("Add items").assertDoesNotExist()
    // Returning must preserve the Details ViewModel rather than loading another entry; the
    // Catalogue loads the same trip once for its own header.
    compose.runOnIdle { assertEquals(listOf(secondTrip.id, secondTrip.id), requestedIds) }
    compose
        .onNodeWithText("Add items to this run")
        .performScrollTo()
        .assertIsEnabled()
        .performClick()
    assertCatalogue()
    pressBack()
    assertTripDetails(secondTrip)
    compose.onNodeWithText("Add items").assertDoesNotExist()
    // Reopening the Catalogue creates a new entry, which loads the same trip once more.
    compose.runOnIdle { assertEquals(List(3) { secondTrip.id }, requestedIds) }
    click(AppTestTags.BACK_BUTTON)
    assertAvailableTripsRestored()

    compose.onTripCard(firstTrip.id).performClick()
    assertTripDetails(firstTrip)
    compose.onNodeWithText(secondTrip.store.name).assertDoesNotExist()
    click(AppTestTags.BACK_BUTTON)
    assertAvailableTripsRestored()
    compose.onTripCard(secondTrip.id).performClick()
    assertTripDetails(secondTrip)
    pressBack()
    assertAvailableTripsRestored()
    compose.runOnIdle {
      // The first three are the Details and the two Catalogue lookups of the opaque trip.
      assertEquals(List(3) { secondTrip.id } + listOf(firstTrip.id, secondTrip.id), requestedIds)
      // The list reloads on every resume: one load on entry plus one per return from Details.
      assertEquals(4, trips.upcomingLoads)
    }
  }

  @Test
  fun detailsBackRestoresAvailableTripsWhileLoadingAndAfterAnError() {
    var detailLoads = 0
    val trips =
        ScriptedTripRepository(
            twoTrips(alice.uid),
            details = {
              detailLoads++
              if (detailLoads == 1) awaitCancellation()
              TripResult.Error(TripError.NetworkError)
            },
        )
    show(FakeAuthRepository(alice), trips)
    click(HomeTestTags.TRIP_ACTION)
    compose.onTripCard(firstTrip.id).performClick()
    compose.onNodeWithText("Loading trip details").assertIsDisplayed()
    compose.onNodeWithTag(AppTestTags.BACK_BUTTON).performClick()
    assertAvailableTripsRestored()
    compose.onTripCard(secondTrip.id).performClick()
    compose.onNodeWithText("Something went wrong while loading this trip.").assertIsDisplayed()
    click(AppTestTags.BACK_BUTTON)
    assertAvailableTripsRestored()
  }

  @Test
  fun accountReplacementFromCatalogueStartsNewAccountOnHomeAndBackCannotRestoreProtectedScreens() =
      runTest {
        val repository = FakeAuthRepository(alice)
        showCatalogueForSession(repository)
        repository.signInWithEmailResult = Result.success(bob)
        repository.signInWithEmail("", "")
        assertScreen(NavigationTestTags.HOME_SCREEN)
        compose.runOnIdle { assertEquals(bob, repository.currentUser) }
        assertTripScreensAbsent()
        pressBack()
        compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
        assertTripScreensAbsent()
      }

  @Test
  fun signOutFromCatalogueShowsLoginAndBackCannotRestoreProtectedScreens() {
    val repository = FakeAuthRepository(alice)
    showCatalogueForSession(repository)
    compose.runOnIdle { repository.signOut() }
    assertScreen(com.android.sample.ui.auth.WelcomeTestTags.SCREEN)
    compose.runOnIdle { assertNull(repository.currentUser) }
    assertTripScreensAbsent()
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
    assertTripScreensAbsent()
  }

  private fun showCatalogueForSession(repository: FakeAuthRepository) {
    show(repository, twoTrips(requireNotNull(repository.currentUser).uid))
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(HomeTestTags.TRIP_ACTION)
    compose.onTripCard(secondTrip.id).performClick()
    assertTripDetails(secondTrip)
    compose.onNodeWithText("Add items to this run").performScrollTo().performClick()
    assertCatalogue()
  }

  private fun assertTripScreensAbsent() {
    compose.onNodeWithText("Add items").assertDoesNotExist()
    compose.onNodeWithTag(CatalogueTestTags.CONTINUE).assertDoesNotExist()
    compose.onNodeWithText("Add items to this run").assertDoesNotExist()
    compose.onNodeWithText(secondTrip.store.name).assertDoesNotExist()
  }

  @Test
  fun sameUidKeepsDetailsButAccountReplacementAndSignOutClearTheDetailsStack() = runTest {
    val repository = FakeAuthRepository(alice)
    val trips = ScriptedTripRepository(twoTrips(alice.uid))
    val requestedIds = trips.requestedIds
    show(repository, trips)
    click(HomeTestTags.TRIP_ACTION)
    compose.onTripCard(secondTrip.id).performClick()
    assertTripDetails(secondTrip)
    repository.signInWithEmailResult = Result.success(alice.copy(displayName = "Alice updated"))
    repository.signInWithEmail("", "")
    assertTripDetails(secondTrip)
    compose.runOnIdle { assertEquals(listOf(secondTrip.id), requestedIds) }

    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithText("Back").assertDoesNotExist()
    click(HomeTestTags.TRIP_ACTION)
    compose.onTripCard(firstTrip.id).performClick()
    assertTripDetails(firstTrip)
    compose.runOnIdle { repository.signOut() }
    assertScreen(com.android.sample.ui.auth.WelcomeTestTags.SCREEN)
    compose.onNodeWithText(firstTrip.store.name).assertDoesNotExist()
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithText("Back").assertDoesNotExist()
    compose.runOnIdle { assertEquals(listOf(secondTrip.id, firstTrip.id), requestedIds) }
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
  }

  @Test
  fun findTripRendersInjectedTripsAndKeepsRepositoryAcrossRecompositionAndNavigation() = runTest {
    val repository = FakeAuthRepository(alice)
    val scripted = ScriptedTripRepository(seededTrips(alice.uid, "Injected store"))
    val requestedIds = scripted.requestedIds
    var homeLoads = 0
    val trips =
        object : TripRepository by scripted {
          override suspend fun getMyTrips(): TripResult<List<Trip>> {
            homeLoads++
            return TripResult.Success(
                listOf(firstTrip.copy(ownerId = alice.uid, status = TripStatus.IN_PROGRESS))
            )
          }
        }
    show(repository, trips)
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).assertTextEquals("Find a trip").performClick()
    compose
        .assertTripCard(SEEDED_TRIP_ID, "Injected store")
        .assert(hasText("Known handoff \u00b7 Handoff point"))
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertIsDisplayed()
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsNotSelected()
    assertScreen(AppTestTags.BOTTOM_BAR)
    compose.runOnIdle { assertEquals(1, scripted.upcomingLoads) }
    compose.onTripCard(SEEDED_TRIP_ID).performClick()
    compose.onNodeWithText("Back").assertIsDisplayed()
    compose.onNodeWithText("Known handoff").assertIsDisplayed()
    compose.runOnIdle { assertEquals(listOf(SEEDED_TRIP_ID), requestedIds) }
    click(AppTestTags.BACK_BUTTON)

    // Updating session state with the same UID recomposes the root without changing accounts.
    repository.signInWithEmailResult = Result.success(alice.copy(displayName = "Alice updated"))
    repository.signInWithEmail("", "")
    compose.assertTripCard(SEEDED_TRIP_ID, "Injected store")
    click(AppTestTags.PROFILE_BUTTON)
    assertScreen(NavigationTestTags.PROFILE_SCREEN)
    pressBack()
    compose.assertTripCard(SEEDED_TRIP_ID, "Injected store")
    click(AppTestTags.HOME_BUTTON)
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(AppTestTags.COMMANDO_MODE)
    compose.onNodeWithTag(HomeTestTags.CURRENT_TRIP).assertIsDisplayed()
    compose.onNodeWithText(firstTrip.store.name).assertIsDisplayed()
    click(AppTestTags.REQUESTER_MODE)
    click(HomeTestTags.TRIP_ACTION)
    compose.assertTripCard(SEEDED_TRIP_ID, "Injected store")
    compose.runOnIdle {
      // The list reloads on every resume: one load on entry plus one per return (Details, Profile).
      assertEquals(4, scripted.upcomingLoads)
      assertTrue(homeLoads > 0)
      assertEquals(listOf(SEEDED_TRIP_ID), requestedIds)
    }
  }

  @Test
  fun locationFilterSurvivesNavigationReloadsOnReturnAndResetsOnSignOut() = runTest {
    val repository = FakeAuthRepository(alice)
    val trips = ScriptedTripRepository(twoTrips(alice.uid))
    show(repository, trips)
    click(HomeTestTags.TRIP_ACTION)
    compose.assertTripCard(secondTrip)
    compose.runOnIdle { assertEquals(1, trips.upcomingLoads) }
    // CE building is about 60 m from the first handoff and over 1 km from the second.
    click(AvailableTripsScreenTestTags.LOCATION_FIELD)
    click(CreateTripScreenTestTags.locationOption("CE building"))
    val assertFiltered = {
      compose
          .onNodeWithTag(AvailableTripsScreenTestTags.LOCATION_FIELD)
          .assert(hasText("CE building"))
      compose.assertTripCard(firstTrip)
      compose.onNodeWithText(secondTrip.store.name).assertDoesNotExist()
    }
    assertFiltered()
    compose.onNodeWithTag(NavigationTestTags.OPEN_MAP).performScrollTo().performClick()
    compose
        .onNodeWithTag(TripMapTestTags.CLEAR_FILTER)
        .assert(hasText("CE building", substring = true))
    compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + firstTrip.id).assertIsDisplayed()
    compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + secondTrip.id).assertDoesNotExist()
    click(NavigationTestTags.MAP_LIST)
    assertFiltered()
    compose.onTripCard(firstTrip.id).performClick()
    assertTripDetails(firstTrip)
    click(AppTestTags.BACK_BUTTON)
    assertFiltered()
    click(AppTestTags.PROFILE_BUTTON)
    assertScreen(NavigationTestTags.PROFILE_SCREEN)
    pressBack()
    assertFiltered()
    compose.runOnIdle { assertEquals(5, trips.upcomingLoads) }

    compose.runOnIdle { repository.signOut() }
    assertScreen(com.android.sample.ui.auth.WelcomeTestTags.SCREEN)
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    click(HomeTestTags.TRIP_ACTION)
    compose
        .onNodeWithTag(AvailableTripsScreenTestTags.LOCATION_FIELD)
        .assert(hasText(compose.activity.getString(R.string.trips_location_placeholder)))
    compose.assertTripCard(secondTrip)
    compose.onNodeWithTag(AvailableTripsScreenTestTags.CLEAR_LOCATION).assertDoesNotExist()
  }

  @Test
  fun mapKeepsSharedChromeAndSwitchesToCommandoTrips() {
    show(FakeAuthRepository(alice), twoTrips(alice.uid))
    click(HomeTestTags.TRIP_ACTION)
    compose.onNodeWithTag(NavigationTestTags.OPEN_MAP).performScrollTo().performClick()
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertIsDisplayed()
    compose.onNodeWithTag(AppTestTags.BOTTOM_BAR).assertIsDisplayed()
    click(AppTestTags.COMMANDO_MODE)
    assertScreen(MyTripsTestTags.SCREEN)
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
    pressBack()
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(TripMapTestTags.HEADER).assertDoesNotExist()
  }

  @Test
  fun mapOpensOpaqueTripDetailsAndRefreshesOnReturn() {
    show(FakeAuthRepository(alice), twoTrips(alice.uid))
    click(HomeTestTags.TRIP_ACTION)
    compose.onNodeWithTag(NavigationTestTags.OPEN_MAP).performScrollTo().performClick()
    compose
        .onNodeWithTag(TripMapTestTags.OFF_MAP_PREFIX + secondTrip.id)
        .performScrollTo()
        .performClick()
    compose.onNodeWithTag(MockMapTestTags.DETAILS).performScrollTo().performClick()
    assertTripDetails(secondTrip)
    pressBack()
    compose.onNodeWithTag(TripMapTestTags.REFRESH).assertIsEnabled()
    compose.onNodeWithTag(NavigationTestTags.MAP_LIST).assertIsDisplayed().performClick()
    for (trip in listOf(firstTrip, secondTrip)) {
      compose
          .onNode(hasScrollAction())
          .performScrollToNode(hasTestTag(AvailableTripsScreenTestTags.tripCard(trip.id)))
      compose.assertTripCard(trip)
    }
  }

  @Test
  fun tripListsAreNotLoadedWhileSignedOutAndAreReloadedForNewSessions() = runTest {
    val repository = FakeAuthRepository()
    val requestedUids = mutableListOf<String>()
    val trips =
        ScriptedTripRepository(
            seededTrips(alice.uid, "alice store"),
            upcoming = {
              val uid = requireNotNull(repository.currentUser).uid
              requestedUids.add(uid)
              seededTrips(uid, "$uid store").getUpcomingTrips()
            },
        )
    show(repository, trips)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.runOnIdle { assertTrue(requestedUids.isEmpty()) }
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(HomeTestTags.TRIP_ACTION)
    compose.assertTripCard(SEEDED_TRIP_ID, "alice store")

    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(HomeTestTags.TRIP_ACTION)
    compose.assertTripCard(SEEDED_TRIP_ID, "bob store")
    compose.onNodeWithText("alice store").assertDoesNotExist()
    compose.runOnIdle { assertEquals(listOf(alice.uid, bob.uid), requestedUids) }

    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(com.android.sample.ui.auth.WelcomeTestTags.SCREEN)
    compose.onNodeWithText("bob store").assertDoesNotExist()
    compose.runOnIdle { assertEquals(listOf(alice.uid, bob.uid), requestedUids) }
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(HomeTestTags.TRIP_ACTION)
    compose.assertTripCard(SEEDED_TRIP_ID, "bob store")
    compose.runOnIdle { assertEquals(listOf(alice.uid, bob.uid, bob.uid), requestedUids) }
  }

  @Test
  fun authDoesNotDisplaySharedBars() {
    show(FakeAuthRepository())
    compose.onNodeWithTag(AppTestTags.APP_SCAFFOLD).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.BOTTOM_BAR).assertDoesNotExist()
  }

  @Test
  fun tripsBottomBarOpensAvailableTripsAndRepeatedClicksKeepOneDestination() {
    show(FakeAuthRepository(alice), twoTrips(alice.uid))
    click(AppTestTags.TRIPS_BUTTON)
    assertAvailableTripsRestored()
    compose.onNodeWithTag(AppTestTags.TRIPS_BUTTON).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsNotEnabled()
    click(AppTestTags.COMMANDO_MODE)
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsSelected()
    assertAvailableTripsRestored()
    click(AppTestTags.TRIPS_BUTTON)
    pressBack()
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsSelected()
    click(AppTestTags.PROFILE_BUTTON)
    click(AppTestTags.TRIPS_BUTTON)
    assertAvailableTripsRestored()
    click(AppTestTags.HOME_BUTTON)
    assertScreen(NavigationTestTags.HOME_SCREEN)
  }

  private val aliceTrip =
      firstTrip.copy(
          id = "alice-trip",
          ownerId = "alice",
          store = Location("Alice store", 46.5, 6.6),
      )

  /** Includes another owner's trip; My Trips must only display the signed-in commando's. */
  private fun aliceTrips(): TripRepository =
      FakeTripRepository("alice", now = { tripTime }, initialTrips = listOf(aliceTrip, firstTrip))

  private fun openMyTrips() {
    click(AppTestTags.COMMANDO_MODE)
    compose.onNodeWithTag(AppTestTags.TRIPS_BUTTON).assertIsEnabled().performClick()
    assertScreen(MyTripsTestTags.SCREEN)
  }

  @Test
  fun tripsOpensMyTripsInCommandoModeAndAvailableTripsForRequester() {
    show(FakeAuthRepository(alice), aliceTrips())
    openMyTrips()
    compose.onNodeWithText("My Trips").assertIsDisplayed()
    compose.onNodeWithText(aliceTrip.store.name).assertIsDisplayed()
    compose.onNodeWithText(firstTrip.store.name).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.TRIPS_BUTTON).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsNotEnabled().performClick()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
    click(AppTestTags.TRIPS_BUTTON)
    pressBack()
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(AppTestTags.REQUESTER_MODE)
    click(AppTestTags.TRIPS_BUTTON)
    compose.onNodeWithTag(MyTripsTestTags.SCREEN).assertDoesNotExist()
    compose.assertTripCard(firstTrip)
  }

  @Test
  fun myTripsSelectionOpensTripDetailsAndPublishOpensCreateTrip() {
    show(FakeAuthRepository(alice), aliceTrips())
    openMyTrips()
    click(MyTripsTestTags.TRIP_CARD_PREFIX + aliceTrip.id)
    assertTripDetails(aliceTrip)
    pressBack()
    assertScreen(MyTripsTestTags.SCREEN)
    click(MyTripsTestTags.PUBLISH_TRIP)
    assertScreen(CreateTripScreenTestTags.STORE_FIELD)
    pressBack()
    assertScreen(MyTripsTestTags.SCREEN)
  }

  @Test
  fun emptyMyTripsPublishOpensCreateTrip() {
    show(FakeAuthRepository(alice), FakeTripRepository("alice", now = { tripTime }))
    openMyTrips()
    assertScreen(MyTripsTestTags.EMPTY)
    click(MyTripsTestTags.PUBLISH_TRIP)
    assertScreen(CreateTripScreenTestTags.STORE_FIELD)
  }

  @Test
  fun changingAccountClearsThePreviousAccountsTrips() = runTest {
    val repository = FakeAuthRepository(alice)
    // The shared fake keeps answering with Alice's trips after Bob signs in.
    show(repository, aliceTrips())
    openMyTrips()
    compose.onNodeWithText(aliceTrip.store.name).assertIsDisplayed()
    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    openMyTrips()
    assertScreen(MyTripsTestTags.EMPTY)
    compose.onNodeWithText(aliceTrip.store.name).assertDoesNotExist()
  }

  @Test
  fun bottomBarTracksDestinationAndHomeReturnsWithoutDuplicatingTheStack() {
    show(FakeAuthRepository(alice))
    compose.onNodeWithTag(AppTestTags.HOME_BUTTON).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.ORDERS_BUTTON).assertIsEnabled()
    compose.onNodeWithText("Orders").assertIsDisplayed()
    compose.onNodeWithText("Map").assertDoesNotExist()
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.PROFILE_BUTTON)
    assertProfile("Alice Smith", "alice@example.com", "AS")
    compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertIsDisplayed()
    click(AppTestTags.PROFILE_BUTTON)
    click(AppTestTags.HOME_BUTTON)
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.HOME_BUTTON).assertIsSelected()
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
  }

  @Test
  fun switchingModeKeepsTheAccountAndProfileBackKeepsTheMode() {
    val repository = FakeAuthRepository(alice)
    show(repository)
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsNotSelected()
    click(AppTestTags.COMMANDO_MODE)
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsNotSelected()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
    compose
        .onNodeWithTag(HomeTestTags.EMPTY_STATE_TITLE)
        .assertTextEquals("Heading to the shop soon?")
    assertEquals(alice, repository.currentUser)
    click(AppTestTags.PROFILE_BUTTON)
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
    click(AppTestTags.REQUESTER_MODE)
    pressBack()
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsSelected()
    compose.onNodeWithTag(HomeTestTags.EMPTY_STATE_TITLE).assertTextEquals("Need a few things?")
    assertEquals(alice, repository.currentUser)
  }

  @Test
  fun profileModeChangeDoesNotRestoreThePreviousModesTripsList() {
    show(FakeAuthRepository(alice))
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.TRIPS_BUTTON)
    click(AppTestTags.PROFILE_BUTTON)
    click(AppTestTags.REQUESTER_MODE)
    assertProfile("Alice Smith", "alice@example.com", "AS")
    pressBack()
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsSelected()
    click(AppTestTags.TRIPS_BUTTON)
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsNotEnabled()
  }

  @Test
  fun signingOutAndBackIntoTheSameAccountResetsTheMode() = runTest {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(com.android.sample.ui.auth.WelcomeTestTags.SCREEN)
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsSelected()
  }

  @Test
  fun changingAccountResetsHomeMode() = runTest {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.COMMANDO_MODE)
    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertIsSelected()
    assertEquals(bob, repository.currentUser)
  }

  @Test
  fun signedOutUserSeesAuthAndCanSwitchForms() {
    show(FakeAuthRepository())
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    click("auth_tab_signup")
    assertScreen(NavigationTestTags.SIGN_UP_SCREEN)
    click("auth_tab_login")
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertDoesNotExist()
  }

  @Test
  fun existingSessionStartsOnHomeAndProfileHeaderReturnsHome() {
    show(FakeAuthRepository(alice))
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(AppTestTags.PROFILE_BUTTON)
    assertProfile("Alice Smith", "alice@example.com", "AS")
    click(NavigationTestTags.BACK_BUTTON)
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(AppTestTags.HOME_BUTTON).assertIsSelected()
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(NavigationTestTags.LOGIN_SCREEN).assertDoesNotExist()
  }

  @Test
  fun signingInRemovesAuthFromBackStack() = runTest {
    val repository = FakeAuthRepository()
    show(repository)
    click("auth_tab_signup")
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
    compose.onNodeWithTag(NavigationTestTags.SIGN_UP_SCREEN).assertDoesNotExist()
  }

  @Test
  fun signingOutFromProfilePreventsBackToProtectedScreens() {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.PROFILE_BUTTON)
    assertProfile("Alice Smith", "alice@example.com", "AS")
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(com.android.sample.ui.auth.WelcomeTestTags.SCREEN)
    compose.onNodeWithTag(NavigationTestTags.SIGN_UP_SCREEN).assertDoesNotExist()
    assertNull(repository.currentUser)
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
    compose.onNodeWithText("Alice Smith").assertDoesNotExist()
    compose.onNodeWithText("alice@example.com").assertDoesNotExist()
    compose.onNodeWithText("AS").assertDoesNotExist()
  }

  @Test
  fun externalSignOutAlsoRemovesProtectedScreens() {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.PROFILE_BUTTON)
    compose.runOnIdle { repository.signOut() }
    assertScreen(com.android.sample.ui.auth.WelcomeTestTags.SCREEN)
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
  }

  @Test
  fun failedSignOutKeepsProfileAndAllowsRetry() {
    val repository = FakeAuthRepository(alice)
    repository.signOutResult = Result.failure(AuthException.Network())
    val picker = Picker()
    show(repository, picker)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.PROFILE_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.SIGN_OUT_ERROR).assertIsDisplayed()
    assertEquals(alice, repository.currentUser)
    compose.runOnIdle { assertEquals(0, picker.clearCalls) }
    repository.signOutResult = Result.success(Unit)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(com.android.sample.ui.auth.WelcomeTestTags.SCREEN)
    compose.runOnIdle { assertEquals(1, picker.clearCalls) }
  }

  @Test
  fun changingAccountClearsPreviousProfileAndStartsAtHome() = runTest {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.PROFILE_BUTTON)
    assertProfile("Alice Smith", "alice@example.com", "AS")
    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
    compose.onNodeWithText("Alice Smith").assertDoesNotExist()
    compose.onNodeWithText("alice@example.com").assertDoesNotExist()
    click(AppTestTags.PROFILE_BUTTON)
    assertProfile("Bob Jones", "bob@example.com", "BJ")
    compose.onNodeWithText("Alice Smith").assertDoesNotExist()
    compose.onNodeWithText("alice@example.com").assertDoesNotExist()
  }

  @Test
  fun profileUpdateRefreshesFieldsAndKeepsNavigationForTheSameAccount() = runTest {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.PROFILE_BUTTON)
    assertProfile("Alice Smith", "alice@example.com", "AS")
    repository.signInWithEmailResult =
        Result.success(alice.copy(displayName = "Alice Brown", email = "alice.brown@example.com"))
    repository.signInWithEmail("", "")
    assertProfile("Alice Brown", "alice.brown@example.com", "AB")
    compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).assertIsSelected()
    compose.onNodeWithText("Alice Smith").assertDoesNotExist()
    compose.onNodeWithText("alice@example.com").assertDoesNotExist()
    click(NavigationTestTags.BACK_BUTTON)
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
  }

  @Test
  fun signingOutRecreatesLoginAndNextAccountDoesNotRestorePreviousProfile() = runTest {
    val repository = FakeAuthRepository()
    show(repository)
    click("auth_tab_signup")
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(AppTestTags.PROFILE_BUTTON)
    assertProfile("Alice Smith", "alice@example.com", "AS")
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(com.android.sample.ui.auth.WelcomeTestTags.SCREEN)
    compose.onNodeWithTag(NavigationTestTags.SIGN_UP_SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
    compose.onNodeWithText("Alice Smith").assertDoesNotExist()
    compose.onNodeWithText("alice@example.com").assertDoesNotExist()
    compose.onNodeWithText("AS").assertDoesNotExist()
    assertNull(repository.currentUser)

    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.LOGIN_SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
    click(AppTestTags.PROFILE_BUTTON)
    assertProfile("Bob Jones", "bob@example.com", "BJ")
    compose.onNodeWithText("Alice Smith").assertDoesNotExist()
    compose.onNodeWithText("alice@example.com").assertDoesNotExist()
    compose.onNodeWithText("AS").assertDoesNotExist()
  }

  @Test
  fun profileEditingValidatesCancelsAndShowsConfirmedValuesAfterSave() {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.PROFILE_BUTTON)
    click(ProfileTestTags.EDIT)
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).assertTextEquals(alice.displayName!!)
    compose.onNodeWithTag(ProfileEditTestTags.EMAIL).assertTextEquals(alice.email!!)
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).performTextReplacement("")
    compose.onNodeWithText("Please enter your full name.").assertIsDisplayed()
    compose.onNodeWithTag(ProfileEditTestTags.EMAIL).performTextReplacement("bad")
    compose.onNodeWithText("Please enter a valid email address.").assertIsDisplayed()
    compose.onNodeWithTag(ProfileEditTestTags.SAVE).assertIsNotEnabled()
    click(ProfileEditTestTags.CANCEL)
    assertProfile("Alice Smith", "alice@example.com", "AS")
    click(ProfileTestTags.EDIT)
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).performTextReplacement("Alice Brown")
    click(ProfileEditTestTags.SAVE)
    compose.onNodeWithText("Your profile has been updated.").assertIsDisplayed()
    click(ProfileEditTestTags.SAVE)
    assertProfile("Alice Brown", "alice@example.com", "AB")
  }

  @Test
  fun failedProfileSaveCanRetryAndPendingEmailIsNotShownAsConfirmed() {
    val repository = FakeAuthRepository(alice)
    repository.updateProfileResult = Result.failure(AuthException.RequiresRecentLogin())
    show(repository)
    click(AppTestTags.PROFILE_BUTTON)
    click(ProfileTestTags.EDIT)
    compose.onNodeWithTag(ProfileEditTestTags.EMAIL).performTextReplacement("new@example.com")
    click(ProfileEditTestTags.SAVE)
    compose
        .onNodeWithText("Please sign out and sign in again before saving your profile.")
        .assertIsDisplayed()
    compose.runOnIdle { repository.updateProfileResult = null }
    click(ProfileEditTestTags.SAVE)
    compose
        .onNodeWithTag(ProfileEditTestTags.FEEDBACK)
        .assertTextEquals(
            "A verification link was sent to new@example.com. Your current email stays unchanged until you verify the new address."
        )
    pressBack()
    assertProfile("Alice Smith", "alice@example.com", "AS")
  }

  @After
  fun resetProvider() {
    AuthRepositoryProvider.reset()
  }

  private fun fillEmailForm() {
    compose.onNodeWithTag("auth_email").performTextInput("student@epfl.ch")
    compose.onNodeWithTag("auth_password").performTextInput("secret123")
  }

  @Test
  fun welcomeOpensBothFormsAndSystemBackReturnsWithoutFinishing() {
    show(FakeAuthRepository(), openForm = false)
    assertScreen(WelcomeTestTags.SCREEN)
    compose.onNodeWithTag(WelcomeTestTags.LOGIN).performScrollTo().performClick()
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    pressBack()
    assertScreen(WelcomeTestTags.SCREEN)
    compose.runOnIdle { assertTrue(!compose.activity.isFinishing) }
    compose.onNodeWithTag(WelcomeTestTags.CREATE_ACCOUNT).performScrollTo().performClick()
    assertScreen(NavigationTestTags.SIGN_UP_SCREEN)
    click("auth_back")
    assertScreen(WelcomeTestTags.SCREEN)
    compose.onNodeWithTag(WelcomeTestTags.LOGIN).performScrollTo().performClick()
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
  }

  @Test
  fun repeatedWelcomeActionsKeepOnlyOneAuthEntry() {
    show(FakeAuthRepository(), openForm = false)
    for ((tag, screen) in
        listOf(
            WelcomeTestTags.LOGIN to NavigationTestTags.LOGIN_SCREEN,
            WelcomeTestTags.CREATE_ACCOUNT to NavigationTestTags.SIGN_UP_SCREEN,
        )) {
      compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(
          SemanticsActions.OnClick
      ) {
        it()
        it()
      }
      assertScreen(screen)
      pressBack()
      assertScreen(WelcomeTestTags.SCREEN)
      compose.runOnIdle { assertTrue(!compose.activity.isFinishing) }
    }
  }

  @Test
  fun defaultAppUsesProviderAndEmailLoginReachesHome() {
    val repository = FakeAuthRepository()
    repository.signInWithEmailResult = Result.success(alice)
    AuthRepositoryProvider.repository = repository
    val trips = FakeTripRepository(currentUserId = alice.uid)
    val picker = Picker()
    compose.setContent {
      SampleAppTheme {
        CommandoApp(
            tripRepository = trips,
            googleCredentials = picker,
        )
      }
    }
    compose.onNodeWithTag(WelcomeTestTags.LOGIN).performScrollTo().performClick()
    fillEmailForm()
    click("auth_submit")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    assertEquals(alice, repository.currentUser)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(com.android.sample.ui.auth.WelcomeTestTags.SCREEN)
    compose.onNodeWithTag(WelcomeTestTags.LOGIN).performScrollTo().performClick()
    compose.onNodeWithTag("auth_email").assertTextEquals("", "Your university email")
    pressBack()
    assertScreen(WelcomeTestTags.SCREEN)
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
  }

  @Test
  fun signupThroughFormReachesHome() {
    val repository = FakeAuthRepository()
    repository.signUpWithEmailResult = Result.success(alice)
    show(repository)
    click("auth_tab_signup")
    compose.onNodeWithTag("auth_full_name").performTextInput("Alice Example")
    fillEmailForm()
    compose.onNodeWithTag("auth_confirmation").performTextInput("secret123")
    click("auth_submit")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    assertEquals(alice, repository.currentUser)
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
  }

  @Test
  fun failedEmailLoginKeepsFormAndCanRetry() {
    val repository = FakeAuthRepository()
    repository.signInWithEmailResult = Result.failure(AuthException.InvalidCredentials())
    show(repository)
    fillEmailForm()
    click("auth_submit")
    compose.onNodeWithText("Incorrect email or password. Please try again.").assertIsDisplayed()
    assertNull(repository.currentUser)
    compose.runOnIdle { repository.signInWithEmailResult = Result.success(alice) }
    click("auth_submit")
    assertScreen(NavigationTestTags.HOME_SCREEN)
  }

  @Test
  fun missingGoogleConfigurationShowsFeedbackAndEmailStillWorks() {
    val repository = FakeAuthRepository()
    repository.signInWithGoogleResult = Result.success(alice)
    show(repository, Picker().apply { error = GoogleSignInNotConfiguredException() })
    click("auth_google")
    compose.runOnIdle {
      assertEquals(
          "Google sign-in needs Firebase configuration. Please use email and password for now.",
          ShadowToast.getTextOfLatestToast(),
      )
      assertNull(repository.currentUser)
    }
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    fillEmailForm()
    repository.signInWithEmailResult = Result.success(alice)
    click("auth_submit")
    assertScreen(NavigationTestTags.HOME_SCREEN)
  }

  @Test
  fun googleCredentialAuthenticatesAndSignOutClearsPickerSession() {
    val repository = FakeAuthRepository()
    repository.signInWithGoogleResult = Result.success(alice)
    val picker = Picker().apply { credential = CustomCredential("google", Bundle()) }
    show(repository, picker)
    click("auth_google")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    assertEquals(alice, repository.currentUser)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(com.android.sample.ui.auth.WelcomeTestTags.SCREEN)
    compose.runOnIdle { assertEquals(1, picker.clearCalls) }
  }

  @Test
  fun dismissingPickerDoesNotAuthenticateAndSignupGoogleCanRetry() {
    val repository = FakeAuthRepository()
    repository.signInWithGoogleResult = Result.success(alice)
    val picker = Picker()
    show(repository, picker)
    click("auth_tab_signup")
    click("auth_google")
    assertScreen(NavigationTestTags.SIGN_UP_SCREEN)
    compose.runOnIdle {
      assertNull(repository.currentUser)
      picker.credential = CustomCredential("google", Bundle())
    }
    click("auth_google")
    assertScreen(NavigationTestTags.HOME_SCREEN)
  }

  @Test
  fun pickerFailureShowsSafeErrorAndAllowsRetry() {
    val repository = FakeAuthRepository()
    repository.signInWithGoogleResult = Result.success(alice)
    val picker = Picker().apply { error = AuthException.InvalidGoogleCredential() }
    show(repository, picker)
    click("auth_google")
    compose.onNodeWithText("Google sign-in failed. Please try again.").assertIsDisplayed()
    compose.runOnIdle {
      assertNull(repository.currentUser)
      picker.error = null
      picker.credential = CustomCredential("google", Bundle())
    }
    click("auth_google")
    assertScreen(NavigationTestTags.HOME_SCREEN)
  }

  @Test
  fun providerCleanupFailureStillSignsOutOfFirebase() {
    val repository = FakeAuthRepository(alice)
    val picker = Picker().apply { clearError = ClearCredentialUnknownException() }
    show(repository, picker)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(com.android.sample.ui.auth.WelcomeTestTags.SCREEN)
    assertNull(repository.currentUser)
    compose.runOnIdle { assertEquals(1, picker.clearCalls) }
  }

  @Test
  fun suspendedProviderCleanupDoesNotDelaySignOutOrPreserveProtectedScreens() {
    val repository = FakeAuthRepository(alice)
    val gate = CompletableDeferred<Unit>()
    val picker = Picker().apply { clearGate = gate }
    show(repository, picker)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    try {
      assertScreen(com.android.sample.ui.auth.WelcomeTestTags.SCREEN)
      compose.runOnIdle {
        assertNull(repository.currentUser)
        assertEquals(1, picker.clearCalls)
        assertTrue(!gate.isCompleted)
      }
      compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
      pressBack()
      compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertDoesNotExist()
    } finally {
      compose.runOnIdle { gate.complete(Unit) }
    }
  }
}
