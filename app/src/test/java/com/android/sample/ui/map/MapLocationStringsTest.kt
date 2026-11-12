package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.android.sample.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises Android quantity selection for both supported map languages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLocationStringsTest {
  private fun nearbyCount(language: String, quantity: Int): String {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val configuration =
        Configuration(context.resources.configuration).apply {
          setLocale(Locale.forLanguageTag(language))
        }
    return context
        .createConfigurationContext(configuration)
        .resources
        .getQuantityString(R.plurals.map_location_nearby_count, quantity, quantity, 500)
  }

  @Test
  fun englishZeroUsesPlural() {
    assertEquals("0 nearby trips · within ≈ 500 m", nearbyCount("en", 0))
  }

  @Test
  fun englishOneUsesSingular() {
    assertEquals("1 nearby trip · within ≈ 500 m", nearbyCount("en", 1))
  }

  @Test
  fun englishTwoUsesPlural() {
    assertEquals("2 nearby trips · within ≈ 500 m", nearbyCount("en", 2))
  }

  @Test
  fun frenchZeroUsesAndroidSingularRule() {
    assertEquals("0 trajet proche · à moins de ≈ 500 m", nearbyCount("fr", 0))
  }

  @Test
  fun frenchOneUsesSingular() {
    assertEquals("1 trajet proche · à moins de ≈ 500 m", nearbyCount("fr", 1))
  }

  @Test
  fun frenchTwoUsesPlural() {
    assertEquals("2 trajets proches · à moins de ≈ 500 m", nearbyCount("fr", 2))
  }
}
