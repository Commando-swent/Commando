package com.android.sample.ui.catalogue

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.sample.model.Product
import com.android.sample.ui.theme.SampleAppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Regression tests for quantities being clipped by a fixed-width quantity label. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp-mdpi")
// Native graphics gives real text metrics, so a too-narrow label actually overflows.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogueQuantityTest {
  @get:Rule val compose = createComposeRule()
  private val bread = Product("migros:2", "migros", "Wholemeal bread", 340)

  private fun showRow(quantity: Int, fontScale: Float = 1f) {
    compose.setContent {
      CompositionLocalProvider(
          LocalDensity provides Density(LocalDensity.current.density, fontScale)
      ) {
        SampleAppTheme { CatalogueProductRow(bread, quantity, {}, {}, {}) }
      }
    }
  }

  /** Fails if the rendered label is cut, i.e. the text needs more room than it was given. */
  private fun assertQuantityFullyShown(quantity: Int) {
    val node = compose.onNodeWithTag(CatalogueTestTags.quantity(bread.id))
    node.assertIsDisplayed().assertTextEquals(quantity.toString())
    val layouts = mutableListOf<TextLayoutResult>()
    node
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsActions.GetTextLayoutResult)
        ?.action
        ?.invoke(layouts)
    val layout = layouts.single()
    // A fixed-width label wraps the overflow onto a hidden second line, so the visible line is cut.
    assertEquals(1, layout.lineCount)
    assertEquals(quantity.toString().length, layout.getLineEnd(0, visibleEnd = true))
    val textWidth = layout.getLineRight(0) - layout.getLineLeft(0)
    val labelWidth = node.fetchSemanticsNode().size.width
    // Line extents are pixel-snapped, so allow 1 px of rounding against the measured label width.
    assertTrue(
        "Quantity $quantity needs ${textWidth}px but its label is ${labelWidth}px",
        textWidth <= labelWidth + 1,
    )
    compose.onNodeWithContentDescription("Decrease Wholemeal bread quantity").assert48dp()
    compose.onNodeWithTag(CatalogueTestTags.increase(bread.id)).assertIsDisplayed().assert48dp()
  }

  @Test
  fun twoDigitQuantityIsNotClippedAtNormalFontScale() {
    showRow(10)
    assertQuantityFullyShown(10)
  }

  @Test
  fun threeDigitQuantityIsNotClippedAtNormalFontScale() {
    showRow(100)
    assertQuantityFullyShown(100)
  }

  @Test
  fun twoDigitQuantityIsNotClippedAtDoubleFontScale() {
    showRow(10, fontScale = 2f)
    assertQuantityFullyShown(10)
  }

  @Test
  fun largeQuantityIsNotClippedAtDoubleFontScale() {
    showRow(9999, fontScale = 2f)
    assertQuantityFullyShown(9999)
  }

  @Test
  fun singleDigitQuantityKeepsTheMinimumLabelWidth() {
    showRow(2)
    compose.onNodeWithTag(CatalogueTestTags.quantity(bread.id)).assertWidthIsAtLeast(24.dp)
    assertQuantityFullyShown(2)
  }

  private fun SemanticsNodeInteraction.assert48dp() =
      assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
}
