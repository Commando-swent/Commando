package com.commando.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the installed APK preserves its identity while resolving the migrated launcher. */
@RunWith(AndroidJUnit4::class)
class AppIdentityInstrumentedTest {
  @Test
  fun applicationIdRemainsCompatibleWithExistingInstallations() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    // This is an installation/Firebase compatibility contract, not the Kotlin namespace.
    assertEquals("com.android.sample", context.packageName)
  }

  @Test
  fun installedLauncherResolvesToTheMigratedActivity() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    assertNotNull("The installed app must expose a launcher activity", launchIntent)
    assertEquals(MainActivity::class.java.name, launchIntent?.component?.className)
  }
}
