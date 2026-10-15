package com.android.sample.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreChainsTest {
  @Test
  fun resolvesEachSupportedChainFromTripStoreNames() {
    assertEquals("migros", storeIdFor("Migros EPFL"))
    assertEquals("coop", storeIdFor("Coop Lausanne"))
    assertEquals("denner", storeIdFor("Denner Chavannes"))
    assertEquals("aldi", storeIdFor("ALDI Suisse, Renens"))
    assertEquals("lidl", storeIdFor("lidl-Crissier"))
    assertEquals("migros", storeIdFor("Migros Ecublens (Migros Partner)"))
  }

  @Test
  fun unknownBlankPartialOrAmbiguousNamesAreUnsupported() {
    listOf("", "   ", "Volg Ecublens", "Migrolino Renens", "Coop & Migros EPFL").forEach {
      assertNull(it, storeIdFor(it))
    }
  }
}
