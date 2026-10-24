package com.android.sample.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.ui.theme.SampleAppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Covers FAQ navigation, restoration and contact details without a submission form. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w360dp-h720dp")
class ProfileHelpScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun supportShowsOnlyTheEmailAddressWithoutAQuestionForm() {
    compose.setContent { SampleAppTheme { ProfileHelpScreen({}, Modifier.height(320.dp)) } }
    compose.onNodeWithText("Contact support").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("info.commando@gmail.com").performScrollTo().assertIsDisplayed()
    compose.onNode(hasSetTextAction()).assertDoesNotExist()
    compose.onNodeWithText("Send question").assertDoesNotExist()
  }

  @Test
  fun questionsExpandAndCollapseWithoutPromisingUnavailablePayments() {
    compose.setContent { SampleAppTheme { ProfileHelpScreen({}) } }
    val answer = compose.activity.getString(R.string.profile_faq_payments_answer)
    val row = compose.onNodeWithTag("${ProfileTestTags.FAQ_QUESTION}_2")
    row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    compose.onNodeWithText(answer).assertDoesNotExist()
    row.performScrollTo().performClick()
    row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    compose.onNodeWithText(answer).assertIsDisplayed()
    row.performClick()
    row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    compose.onNodeWithText(answer).assertDoesNotExist()
  }

  @Test
  fun recreationRestoresTheExpandedAnswer() {
    val restoration = StateRestorationTester(compose)
    restoration.setContent { SampleAppTheme { ProfileHelpScreen({}) } }
    compose.onNodeWithTag("${ProfileTestTags.FAQ_QUESTION}_0").performClick()
    restoration.emulateSavedInstanceStateRestore()
    compose
        .onNodeWithTag("${ProfileTestTags.FAQ_QUESTION}_0")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    compose
        .onNodeWithText(compose.activity.getString(R.string.profile_faq_trips_answer))
        .assertExists()
  }
}
