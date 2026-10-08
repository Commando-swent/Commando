package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.home.HomeTestTags
import com.android.sample.ui.profile.ProfileTestTags
import com.android.sample.ui.theme.SampleAppTheme
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
  private val alice = AuthUser("alice", displayName = "Alice Smith", email = "alice@example.com")
  private val bob = AuthUser("bob", displayName = "Bob Jones", email = "bob@example.com")

  private fun show(repository: FakeAuthRepository) {
    compose.setContent { SampleAppTheme { CommandoApp(repository) } }
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
    assertProfile("Alice Smith", "alice@example.com", "AS")
    compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).assertIsSelected()
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertDoesNotExist()
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
    assertProfile("Alice Smith", "alice@example.com", "AS")
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
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
    click(NavigationTestTags.AUTH_MODE_BUTTON)
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    click(AppTestTags.PROFILE_BUTTON)
    assertProfile("Alice Smith", "alice@example.com", "AS")
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
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
}
