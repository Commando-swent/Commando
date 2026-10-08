package com.android.sample.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.model.authentication.*
import com.android.sample.ui.navigation.ProfileEditRoute
import com.android.sample.ui.theme.SampleAppTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileEditRestorationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val user = AuthUser("alice", "Alice Martin", "alice@example.test")

  @Test
  fun restoredDestinationWaitsForAccountAndInitializesMissingDraft() {
    val users = MutableSharedFlow<AuthUser?>(replay = 1)
    val repository =
        object : AuthRepository by FakeAuthRepository(user) {
          override fun observeAuthState() = users
        }
    val model = ProfileViewModel(repository)
    compose.setContent { SampleAppTheme { ProfileEditRoute(model) {} } }
    compose.onNodeWithTag(ProfileEditTestTags.LOADING).assertIsDisplayed()
    compose.runOnIdle { users.tryEmit(user) }
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).assertTextEquals(user.displayName!!)
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).performTextReplacement("Draft")
    compose.runOnIdle { users.tryEmit(user.copy(displayName = "Updated externally")) }
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).assertTextEquals("Draft")
  }

  @Test
  fun backInSameFrameAsSaveIsBlockedAndFailedSaveAllowsReturn() {
    val pending = CompletableDeferred<Result<ProfileUpdateResult>>()
    val repository =
        object : AuthRepository by FakeAuthRepository(user) {
          override suspend fun updateProfile(fullName: String, email: String) = pending.await()
        }
    val model = ProfileViewModel(repository)
    var returns = 0
    compose.setContent { SampleAppTheme { ProfileEditRoute(model) { returns++ } } }
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).assertTextEquals(user.displayName!!)
    compose.runOnIdle {
      model.saveProfile()
      compose.activity.onBackPressedDispatcher.onBackPressed()
      assertEquals(0, returns)
    }
    compose.onNodeWithTag(ProfileEditTestTags.SAVING).assertIsDisplayed()
    compose.runOnIdle { pending.complete(Result.failure(AuthException.Network())) }
    compose.onNodeWithTag(ProfileEditTestTags.FEEDBACK).assertIsDisplayed()
    compose.onNodeWithTag(ProfileEditTestTags.CANCEL).performClick()
    compose.runOnIdle { assertEquals(1, returns) }
  }
}
