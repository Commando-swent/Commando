package com.android.sample.model.authentication

// AI assistance: Claude (Anthropic).
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the Google Sign-In client configuration; does not need the Firebase Auth emulator. */
@RunWith(AndroidJUnit4::class)
class GoogleSignInConfigurationTest {

  @Test
  fun defaultWebClientId_hasGoogleClientIdFormat() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val resourceId =
        context.resources.getIdentifier("default_web_client_id", "string", context.packageName)

    // Skipped until a Google OAuth client is added to google-services.json.
    assumeTrue("Google Sign-In not configured - skipping test", resourceId != 0)

    val clientId = context.getString(resourceId)
    assertTrue(
        "Invalid Google client ID format: $clientId",
        clientId.endsWith(".googleusercontent.com"),
    )
  }
}
