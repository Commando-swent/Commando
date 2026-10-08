package com.android.sample.ui.profile

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileInitialsTest {
  @Test
  fun missingNameHasNoInitials() {
    for (name in listOf(null, "", " ", "\t\n  ")) {
      assertNull(profileInitials(name))
    }
  }

  @Test
  fun singleWordUsesOnlyItsFirstLetter() {
    assertEquals("Y", profileInitials("yasmine"))
    assertEquals("A", profileInitials("A"))
  }

  @Test
  fun twoWordsUseBothInitials() {
    assertEquals("AM", profileInitials("Alice Martin"))
  }

  @Test
  fun multipleWordsUseFirstAndLastInitials() {
    assertEquals("ÉM", profileInitials("Élodie van der Meer"))
  }

  @Test
  fun leadingTrailingAndRepeatedWhitespaceDoesNotCreateInitials() {
    assertEquals("AM", profileInitials(" \tAlice  \n  Martin \t "))
    assertEquals("Y", profileInitials(" \n yasmine \t "))
  }

  @Test
  fun unicodeSpacesSeparateNameWords() {
    assertEquals("AM", profileInitials("Alice\u00A0Martin"))
    assertEquals("山太", profileInitials("山田\u3000太郎"))
  }

  @Test
  fun accentedInitialsArePreservedAndUppercased() {
    assertEquals("ÉÖ", profileInitials("élodie özdemir"))
  }

  @Test
  fun uppercasingDoesNotDependOnTheDeviceLocale() {
    val originalLocale = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      assertEquals("II", profileInitials("irem ince"))
    } finally {
      Locale.setDefault(originalLocale)
    }
  }
}
