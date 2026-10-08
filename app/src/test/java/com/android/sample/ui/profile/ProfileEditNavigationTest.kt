package com.android.sample.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.navigation.AppTestTags
import com.android.sample.ui.navigation.CommandoApp
import com.android.sample.ui.navigation.NavigationTestTags
import com.android.sample.ui.theme.SampleAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileEditNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val user = AuthUser("alice", "Alice Martin", "alice@example.test")
  private val repository = FakeAuthRepository(user)

  private fun openEditor() {
    compose.setContent {
      SampleAppTheme { CommandoApp(repository, FakeTripRepository(currentUserId = user.uid)) }
    }
    compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).performClick()
    compose.onNodeWithTag(ProfileTestTags.EDIT).performClick()
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).assertTextEquals("Alice Martin")
  }

  @Test
  fun cancelDiscardsDraftAndReopeningUsesConfirmedValues() {
    openEditor()
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).performTextReplacement("Draft")
    compose.onNodeWithTag(ProfileEditTestTags.CANCEL).performClick()
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("Alice Martin")
    compose.onNodeWithTag(ProfileTestTags.EDIT).performClick()
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).assertTextEquals("Alice Martin")
    compose.onNodeWithTag(ProfileEditTestTags.BACK).performClick()
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertIsDisplayed()
    compose.runOnIdle { assertEquals(user, repository.currentUser) }
  }

  @Test
  fun nameSaveReturnsConfirmedValuesToProfile() {
    openEditor()
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).performTextReplacement("Alice Dupont")
    compose.onNodeWithTag(ProfileEditTestTags.SAVE).performClick()
    compose.onNodeWithTag(ProfileEditTestTags.DONE).performClick()
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("Alice Dupont")
    compose.runOnIdle { assertEquals("Alice Dupont", repository.currentUser?.displayName) }
  }

  @Test
  fun emailSaveShowsPendingFeedbackAndPreservesConfirmedEmail() {
    openEditor()
    compose.onNodeWithTag(ProfileEditTestTags.EMAIL).performTextReplacement("new@example.test")
    compose.onNodeWithTag(ProfileEditTestTags.SAVE).performClick()
    compose
        .onNodeWithTag(ProfileEditTestTags.FEEDBACK)
        .assertTextContains("new@example.test", substring = true)
    compose.onNodeWithTag(ProfileEditTestTags.DONE).performClick()
    compose.onNodeWithTag(ProfileTestTags.EMAIL).assertTextEquals("alice@example.test")
  }
}
