package com.android.sample.ui.profile

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.R
import com.android.sample.ui.theme.SampleAppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies both language actions and localized formatted resources, including plural rules. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w360dp-h720dp")
class ProfileLanguageScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun selectionUsesCallerStateAndSupportsSwitchingBothWaysAndBack() {
    val selected = mutableStateOf(AppLanguage.English)
    var backCalls = 0
    compose.setContent {
      SampleAppTheme {
        ProfileLanguageScreen(selected.value, { selected.value = it }, { backCalls++ })
      }
    }
    val english = compose.onNodeWithTag("${ProfileTestTags.LANGUAGE_OPTION}_en")
    val french = compose.onNodeWithTag("${ProfileTestTags.LANGUAGE_OPTION}_fr")
    compose.onNodeWithText("English").assertIsDisplayed()
    compose.onNodeWithText("Anglais").assertIsDisplayed()
    compose.onNodeWithText("French").assertIsDisplayed()
    compose.onNodeWithText("Français").assertIsDisplayed()
    english.assertIsSelected()
    french.assertIsNotSelected().performClick().assertIsSelected()
    english.assertIsNotSelected().performClick().assertIsSelected()
    compose.onNodeWithTag(ProfileTestTags.SETTINGS_BACK).performClick()
    compose.runOnIdle { assertEquals(1, backCalls) }
  }

  @Test
  fun frenchStringsKeepArgumentsAndUseFrenchPlurals() {
    val config =
        Configuration(compose.activity.resources.configuration).apply {
          setLocales(LocaleList.forLanguageTags("fr"))
        }
    val resources = compose.activity.createConfigurationContext(config).resources
    assertEquals("Profil", resources.getString(R.string.nav_profile))
    assertEquals("1 article", resources.getQuantityString(R.plurals.catalogue_item_count, 1, 1))
    assertEquals("3 articles", resources.getQuantityString(R.plurals.catalogue_item_count, 3, 3))
    assertEquals(
        "2 RÉSULTATS POUR « lait »",
        resources.getQuantityString(R.plurals.catalogue_results, 2, 2, "lait"),
    )
    assertTrue(
        resources
            .getString(R.string.profile_edit_verification, "élodie@example.com")
            .contains("élodie@example.com")
    )
    assertEquals(AppLanguage.French, AppLanguage.fromLanguage("fr"))
    assertEquals(AppLanguage.English, AppLanguage.fromLanguage("en"))
    assertEquals(AppLanguage.English, AppLanguage.fromLanguage("de"))
  }
}
