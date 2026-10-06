package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.home.HomeTestTags
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
  private val alice = AuthUser("alice")
  private val bob = AuthUser("bob")

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

  @Test
  fun switchingModeKeepsTheAccountAndProfileBackKeepsTheMode() {
    val repository = FakeAuthRepository(alice)
    show(repository)
    compose.onNodeWithTag(HomeTestTags.REQUESTER_MODE).assertIsSelected()
    click(HomeTestTags.COMMANDO_MODE)
    compose
        .onNodeWithTag(HomeTestTags.EMPTY_STATE_TITLE)
        .assertTextEquals("Heading to the shop soon?")
    assertEquals(alice, repository.currentUser)
    click(NavigationTestTags.PROFILE_BUTTON)
    pressBack()
    compose.onNodeWithTag(HomeTestTags.COMMANDO_MODE).assertIsSelected()
    click(HomeTestTags.REQUESTER_MODE)
    compose.onNodeWithTag(HomeTestTags.EMPTY_STATE_TITLE).assertTextEquals("Need a few things?")
    assertEquals(alice, repository.currentUser)
  }

  @Test
  fun signingOutAndBackIntoTheSameAccountResetsTheMode() = runTest {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(HomeTestTags.COMMANDO_MODE)
    click(NavigationTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    repository.signInWithEmailResult = Result.success(alice)
    repository.signInWithEmail("", "")
    compose.onNodeWithTag(HomeTestTags.REQUESTER_MODE).assertIsSelected()
  }

  @Test
  fun changingAccountResetsHomeMode() = runTest {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(HomeTestTags.COMMANDO_MODE)
    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    compose.onNodeWithTag(HomeTestTags.REQUESTER_MODE).assertIsSelected()
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
    click(NavigationTestTags.PROFILE_BUTTON)
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
    click(NavigationTestTags.PROFILE_BUTTON)
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
    click(NavigationTestTags.PROFILE_BUTTON)
    compose.runOnIdle { repository.signOut() }
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
  }

  @Test
  fun failedSignOutKeepsProfileAndAllowsRetry() {
    val repository = FakeAuthRepository(alice)
    repository.signOutResult = Result.failure(AuthException.Network())
    show(repository)
    click(NavigationTestTags.PROFILE_BUTTON)
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
    click(NavigationTestTags.PROFILE_BUTTON)
    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    assertScreen(NavigationTestTags.HOME_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
  }

  @Test
  fun profileUpdateKeepsNavigationForTheSameAccount() = runTest {
    val repository = FakeAuthRepository(alice)
    show(repository)
    click(NavigationTestTags.PROFILE_BUTTON)
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
    click(NavigationTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertScreen(NavigationTestTags.LOGIN_SCREEN)
    compose.onNodeWithTag(NavigationTestTags.SIGN_UP_SCREEN).assertDoesNotExist()
  }
}
