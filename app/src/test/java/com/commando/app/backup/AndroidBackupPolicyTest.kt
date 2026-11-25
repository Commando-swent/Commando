package com.commando.app.backup

// AI assistance: OpenAI Codex.
import android.content.Context
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import com.commando.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/** Guards the app's packaged backup rules against accidental inclusion of private storage. */
@RunWith(RobolectricTestRunner::class)
class AndroidBackupPolicyTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun legacyBackupExcludesAllAppStorageDomains() {
    context.resources.getXml(R.xml.backup_rules).use { rules ->
      assertEquals("full-backup-content", rules.nextTagName())
      assertExcludesAllDomains(rules)
    }
  }

  @Test
  fun cloudBackupAndDeviceTransferEachExcludeAllAppStorageDomains() {
    context.resources.getXml(R.xml.data_extraction_rules).use { rules ->
      assertEquals("data-extraction-rules", rules.nextTagName())
      val checkedModes = mutableListOf<String>()
      while (rules.nextElement() == XmlPullParser.START_TAG) {
        checkedModes += rules.name
        assertExcludesAllDomains(rules)
      }
      assertEquals(setOf("cloud-backup", "device-transfer"), checkedModes.toSet())
      assertEquals(2, checkedModes.size)
    }
  }

  private fun assertExcludesAllDomains(rules: XmlResourceParser) {
    val exclusions = mutableListOf<String>()
    while (rules.nextElement() == XmlPullParser.START_TAG) {
      assertEquals("exclude", rules.name)
      assertEquals(".", rules.getAttributeValue(null, "path"))
      exclusions += rules.getAttributeValue(null, "domain")
      assertEquals(XmlPullParser.END_TAG, rules.nextElement())
    }
    assertEquals(appStorageDomains, exclusions.toSet())
    assertEquals(appStorageDomains.size, exclusions.size)
  }

  private fun XmlResourceParser.nextTagName(): String {
    assertEquals(XmlPullParser.START_TAG, nextElement())
    return name
  }

  private fun XmlResourceParser.nextElement(): Int {
    var event = next()
    while (
        event != XmlPullParser.START_TAG &&
            event != XmlPullParser.END_TAG &&
            event != XmlPullParser.END_DOCUMENT
    ) {
      event = next()
    }
    return event
  }

  private companion object {
    val appStorageDomains =
        setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )
  }
}
