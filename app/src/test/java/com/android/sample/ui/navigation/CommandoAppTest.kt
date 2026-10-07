package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.home.HomeTestTags
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommandoAppTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val alice = AuthUser("alice")
  private val bob = AuthUser("bob")

  private fun show(repository: FakeAuthRepository) {
    compose.setContent { SampleAppTheme { CommandoApp(repository) } }
  }

  private fun show(
      repository: FakeAuthRepository,
      tripRepositoryFactory: (String) -> TripRepository,
  ) {
    compose.setContent { SampleAppTheme { CommandoApp(repository, tripRepositoryFactory) } }
  }

  private fun seededTrips(uid: String, storeName: String): TripRepository {
    val now = Instant.parse("2026-10-07T12:00:00Z")
    val trip =
        Trip(
            id = "known-trip",
            ownerId = "trip-owner",
            store = Location(storeName, 46.52, 6.63),
            scheduledAt = now.plusSeconds(3600),
            handoffLocation = Location("Known handoff", 46.52, 6.57),
            status = TripStatus.PUBLISHED,
            createdAt = now,
            updatedAt = now,
        )
    return FakeTripRepository(uid, now = { now }, initialTrips = listOf(trip))
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

  private val tripTime = Instant.parse("2026-10-07T12:00:00Z")
  private val firstTrip =
      Trip(
          id = "trip-a",
          ownerId = "owner-a",
          store = Location("First store", 46.52, 6.63),
          scheduledAt = tripTime.plusSeconds(3600),
          handoffLocation = Location("First handoff", 46.52, 6.57),
          status = TripStatus.PUBLISHED,
          createdAt = tripTime,
          updatedAt = tripTime,
      )
  private val secondTrip =
      firstTrip.copy(
          id = " /%2F 雪% trip / ",
          store = Location("Second store", 46.53, 6.64),
          scheduledAt = tripTime.plusSeconds(7200),
          handoffLocation = Location("Second handoff", 46.53, 6.58),
      )

  private fun twoTrips(uid: String): TripRepository =
      FakeTripRepository(uid, now = { tripTime }, initialTrips = listOf(firstTrip, secondTrip))

  private fun assertTripDetails(trip: Trip) {
    assertTripScaffoldHidden()
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

  private fun assertAddItemsPlaceholder() {
    compose.onNodeWithText("Add items").assertIsDisplayed().assertTextEquals("Add items")
    compose
        .onNodeWithText("This feature will be available in Sprint 2.")
        .assertIsDisplayed()
        .assertTextEquals("This feature will be available in Sprint 2.")
    compose.onNodeWithText("Back").assertIsDisplayed()
    compose.onNodeWithText("Add items to this run").assertDoesNotExist()
    compose.onNodeWithText(secondTrip.store.name).assertDoesNotExist()
    assertTripScaffoldHidden()
  }

  private fun assertAvailableTripsRestored() {
    compose.onNodeWithText("Back").assertDoesNotExist()
    compose.onNodeWithText(firstTrip.store.name).assertIsDisplayed()
    compose.onNodeWithText(secondTrip.store.name).assertIsDisplayed()
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertDoesNotExist()
    assertScreen(AppTestTags.BOTTOM_BAR)
  }

  @Test
  fun selectedOpaqueTripIsLoadedExactlyAndBothBackActionsRestoreTheExistingList() {
    val requestedIds = mutableListOf<String>()
    var listLoads = 0
    val fake = twoTrips(alice.uid)
    val trips =
        object : TripRepository by fake {
          override suspend fun getUpcomingTrips(): TripResult<List<Trip>> {
            listLoads++
            return fake.getUpcomingTrips()
          }

          override suspend fun getTripById(tripId: String): TripResult<Trip> {
            requestedIds.add(tripId)
            return fake.getTripById(tripId)
          }
        }
    show(FakeAuthRepository(alice)) { trips }
    click(HomeTestTags.TRIP_ACTION)
    assertAvailableTripsRestored()
    compose.onNodeWithText(secondTrip.store.name).performClick()
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
    assertAddItemsPlaceholder()
    compose.onNodeWithText("Back").performClick()
    assertTripDetails(secondTrip)
    compose.onNodeWithText("Add items").assertDoesNotExist()
    // Returning must preserve the Details ViewModel rather than loading another entry.
    compose.runOnIdle { assertEquals(listOf(secondTrip.id), requestedIds) }
    compose
        .onNodeWithText("Add items to this run")
        .performScrollTo()
        .assertIsEnabled()
        .performClick()
    assertAddItemsPlaceholder()
    pressBack()
    assertTripDetails(secondTrip)
    compose.onNodeWithText("Add items").assertDoesNotExist()
    compose.runOnIdle { assertEquals(listOf(secondTrip.id), requestedIds) }
    compose.onNodeWithText("Back").performClick()
    assertAvailableTripsRestored()

    compose.onNodeWithText(firstTrip.store.name).performClick()
    assertTripDetails(firstTrip)
    compose.onNodeWithText(secondTrip.store.name).assertDoesNotExist()
    compose.onNodeWithText("Back").performClick()
    assertAvailableTripsRestored()
    compose.onNodeWithText(secondTrip.store.name).performClick()
    assertTripDetails(secondTrip)
    pressBack()
    assertAvailableTripsRestored()
    compose.runOnIdle {
      assertEquals(listOf(secondTrip.id, firstTrip.id, secondTrip.id), requestedIds)
      assertEquals(1, listLoads)
    }
  }

  @Test
  fun detailsBackRestoresAvailableTripsWhileLoadingAndAfterAnError() {
    var detailLoads = 0
    val fake = twoTrips(alice.uid)
    val trips =
        object : TripRepository by fake {
          override suspend fun getTripById(tripId: String): TripResult<Trip> {
            detailLoads++
            if (detailLoads == 1) awaitCancellation()
            return TripResult.Error(TripError.NetworkError)
          }
        }
    show(FakeAuthRepository(alice)) { trips }
    click(HomeTestTags.TRIP_ACTION)
    compose.onNodeWithText(firstTrip.store.name).performClick()
    compose.onNodeWithText("Loading trip details").assertIsDisplayed()
    compose.onNodeWithText("Back").performClick()
    assertAvailableTripsRestored()
    compose.onNodeWithText(secondTrip.store.name).performClick()
    compose.onNodeWithText("Something went wrong while loading this trip.").assertIsDisplayed()
    compose.onNodeWithText("Back").performClick()
    assertAvailableTripsRestored()
  }

  @Test
  fun accountReplacementFromAddItemsStartsNewAccountOnHomeAndBackCannotRestoreProtectedScreens() =
      runTest {
        val repository = FakeAuthRepository(alice)
        showAddItemsForSession(repository)
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
  fun signOutFromAddItemsShowsLoginAndBackCannotRestoreProtectedScreens() {
    val repository = FakeAuthRepository(alice)
    showAddItemsForSession(repository)
    compose.runOnIdle { repository.signOut() }
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.runOnIdle { assertNull(repository.currentUser) }
    assertTripScreensAbsent()
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
    assertTripScreensAbsent()
  }

  private fun showAddItemsForSession(repository: FakeAuthRepository) {
    show(repository) { uid -> twoTrips(uid) }
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(HomeTestTags.TRIP_ACTION)
    compose.onNodeWithText(secondTrip.store.name).performClick()
    assertTripDetails(secondTrip)
    compose.onNodeWithText("Add items to this run").performScrollTo().performClick()
    assertAddItemsPlaceholder()
  }

  private fun assertTripScreensAbsent() {
    compose.onNodeWithText("Add items").assertDoesNotExist()
    compose.onNodeWithText("This feature will be available in Sprint 2.").assertDoesNotExist()
    compose.onNodeWithText("Add items to this run").assertDoesNotExist()
    compose.onNodeWithText(secondTrip.store.name).assertDoesNotExist()
  }

  @Test
  fun sameUidKeepsDetailsButAccountReplacementAndSignOutClearTheDetailsStack() = runTest {
    val repository = FakeAuthRepository(alice)
    val requestedUids = mutableListOf<String>()
    show(repository) { uid ->
      requestedUids.add(uid)
      twoTrips(uid)
    }
    click(HomeTestTags.TRIP_ACTION)
    compose.onNodeWithText(secondTrip.store.name).performClick()
    assertTripDetails(secondTrip)
    repository.signInWithEmailResult = Result.success(alice.copy(displayName = "Alice updated"))
    repository.signInWithEmail("", "")
    assertTripDetails(secondTrip)
    compose.runOnIdle { assertEquals(listOf(alice.uid), requestedUids) }

    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithText("Back").assertDoesNotExist()
    click(HomeTestTags.TRIP_ACTION)
    compose.onNodeWithText(firstTrip.store.name).performClick()
    assertTripDetails(firstTrip)
    compose.runOnIdle { repository.signOut() }
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.onNodeWithText(firstTrip.store.name).assertDoesNotExist()
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithText("Back").assertDoesNotExist()
    compose.runOnIdle { assertEquals(listOf(alice.uid, bob.uid, alice.uid), requestedUids) }
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
  }

  @Test
  fun findTripRendersInjectedTripsAndKeepsRepositoryAcrossRecompositionAndNavigation() = runTest {
    val repository = FakeAuthRepository(alice)
    val requestedUids = mutableListOf<String>()
    show(repository) { uid ->
      requestedUids.add(uid)
      seededTrips(uid, "Injected store")
    }
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).assertTextEquals("Find a trip").performClick()
    compose.onNodeWithText("Injected store").assertIsDisplayed()
    compose.onNodeWithText("Known handoff \u00b7 Handoff point").assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.REQUESTER_MODE).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertDoesNotExist()
    assertScreen(AppTestTags.BOTTOM_BAR)
    compose.runOnIdle { assertEquals(listOf(alice.uid), requestedUids) }

    // Updating session state with the same UID recomposes the root without changing accounts.
    repository.signInWithEmailResult = Result.success(alice.copy(displayName = "Alice updated"))
    repository.signInWithEmail("", "")
    compose.onNodeWithText("Injected store").assertIsDisplayed()
    click(AppTestTags.PROFILE_BUTTON)
    assertScreen(NavigationTestTags.PROFILE_SCREEN)
    pressBack()
    compose.onNodeWithText("Injected store").assertIsDisplayed()
    click(AppTestTags.HOME_BUTTON)
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.REQUESTER_MODE)
    click(HomeTestTags.TRIP_ACTION)
    compose.onNodeWithText("Injected store").assertIsDisplayed()
    compose.runOnIdle { assertEquals(listOf(alice.uid), requestedUids) }
  }

  @Test
  fun tripRepositoryIsCreatedOnlyForAuthenticatedSessionsAndReplacedOnSessionChanges() = runTest {
    val repository = FakeAuthRepository()
    val requestedUids = mutableListOf<String>()
    show(repository) { uid ->
      requestedUids.add(uid)
      seededTrips(uid, "$uid store")
    }
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.runOnIdle { assertTrue(requestedUids.isEmpty()) }
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(HomeTestTags.TRIP_ACTION)
    compose.onNodeWithText("alice store").assertIsDisplayed()

    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(HomeTestTags.TRIP_ACTION)
    compose.onNodeWithText("bob store").assertIsDisplayed()
    compose.onNodeWithText("alice store").assertDoesNotExist()
    compose.runOnIdle { assertEquals(listOf(alice.uid, bob.uid), requestedUids) }

    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.onNodeWithText("bob store").assertDoesNotExist()
    compose.runOnIdle { assertEquals(listOf(alice.uid, bob.uid), requestedUids) }
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(HomeTestTags.TRIP_ACTION)
    compose.onNodeWithText("bob store").assertIsDisplayed()
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
  fun bottomBarTracksDestinationAndHomeReturnsWithoutDuplicatingTheStack() {
    show(FakeAuthRepository(alice))
    compose.onNodeWithTag(AppTestTags.HOME_BUTTON).assertIsSelected()
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.PROFILE_BUTTON)
    compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertDoesNotExist()
    click(AppTestTags.PROFILE_BUTTON)
    click(AppTestTags.HOME_BUTTON)
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
    pressBack()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
    click(AppTestTags.REQUESTER_MODE)
    compose.onNodeWithTag(HomeTestTags.EMPTY_STATE_TITLE).assertTextEquals("Need a few things?")
    assertEquals(alice, repository.currentUser)
  }

  @Test
  fun signingOutAndBackIntoTheSameAccountResetsTheMode() = runTest {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
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
    click(NavigationTestTags.AUTH_MODE_BUTTON)
    assertScreen(NavigationTestTags.SIGN_UP_SCREEN)
    click(NavigationTestTags.AUTH_MODE_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertDoesNotExist()
  }

  @Test
  fun existingSessionStartsOnHomeAndProfileReturnsWithBack() {
    show(FakeAuthRepository(alice))
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(AppTestTags.PROFILE_BUTTON)
    assertScreen(NavigationTestTags.PROFILE_SCREEN)
    pressBack()
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.LOGIN_SCREEN).assertDoesNotExist()
  }

  @Test
  fun signingInRemovesAuthFromBackStack() = runTest {
    val repository = FakeAuthRepository()
    show(repository)
    click(NavigationTestTags.AUTH_MODE_BUTTON)
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
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    assertNull(repository.currentUser)
    pressBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
  }

  @Test
  fun externalSignOutAlsoRemovesProtectedScreens() {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.PROFILE_BUTTON)
    compose.runOnIdle { repository.signOut() }
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
  }

  @Test
  fun failedSignOutKeepsProfileAndAllowsRetry() {
    val repository = FakeAuthRepository(alice)
    repository.signOutResult = Result.failure(AuthException.Network())
    show(repository)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.PROFILE_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.SIGN_OUT_ERROR).assertIsDisplayed()
    assertEquals(alice, repository.currentUser)
    repository.signOutResult = Result.success(Unit)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
  }

  @Test
  fun changingAccountStartsAtHomeInsteadOfPreviousProfile() = runTest {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.PROFILE_BUTTON)
    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
  }

  @Test
  fun profileUpdateKeepsNavigationForTheSameAccount() = runTest {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(AppTestTags.PROFILE_BUTTON)
    repository.signInWithEmailResult = Result.success(alice.copy(displayName = "Alice"))
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.PROFILE_SCREEN)
  }

  @Test
  fun signingOutRecreatesTheLoginForm() = runTest {
    val repository = FakeAuthRepository()
    show(repository)
    click(NavigationTestTags.AUTH_MODE_BUTTON)
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.SIGN_UP_SCREEN).assertDoesNotExist()
  }
}
