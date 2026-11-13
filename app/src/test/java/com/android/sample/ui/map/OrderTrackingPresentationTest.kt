package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import com.android.sample.R
import com.android.sample.model.CommandoPosition
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Freshness and action eligibility use the original capture rather than listener delivery time. */
class OrderTrackingPresentationTest {
  private val now = Instant.parse("2026-11-13T10:00:00Z")
  private val fix = CommandoPosition(46.521, 6.566, 30.2, now.minusSeconds(15))

  @Test
  fun freshCampusFixShowsAgeConservativeAccuracyAndRecenter() {
    val shown = orderTrackingPresentation(OrderTrackingState.Position(fix), now)
    assertEquals(R.string.order_tracking_live, shown.message)
    assertEquals(15, shown.ageSeconds)
    assertEquals(31, shown.accuracyMeters)
    assertTrue(shown.canRecenter)
    assertFalse(shown.canRetry)
  }

  @Test
  fun unchangedFixAgesWithoutARepositoryUpdate() {
    val state = OrderTrackingState.Position(fix)
    assertEquals(16, orderTrackingPresentation(state, now.plusSeconds(1)).ageSeconds)
    assertEquals(119, orderTrackingPresentation(state, fix.capturedAt.plusSeconds(119)).ageSeconds)
  }

  @Test
  fun exactExpiryRemovesCoordinatesMetadataAndRecenter() {
    val shown =
        orderTrackingPresentation(OrderTrackingState.Position(fix), fix.capturedAt.plusSeconds(120))
    assertEquals(R.string.order_tracking_unavailable, shown.message)
    assertNull(shown.fix)
    assertNull(shown.ageSeconds)
    assertNull(shown.accuracyMeters)
    assertFalse(shown.canRecenter)
    assertTrue(shown.canRetry)
  }

  @Test
  fun futureAndInvalidFixesNeverOfferRecenter() {
    for (invalid in
        listOf(fix.copy(capturedAt = now.plusSeconds(1)), fix.copy(accuracyMeters = Double.NaN))) {
      val shown = orderTrackingPresentation(OrderTrackingState.Position(invalid), now)
      assertNull(shown.fix)
      assertFalse(shown.canRecenter)
    }
  }

  @Test
  fun offCampusFixRetainsFreshnessWithoutOfferingRecenter() {
    val shown =
        orderTrackingPresentation(OrderTrackingState.Position(fix.copy(latitude = 0.0)), now)
    assertEquals(R.string.order_tracking_off_map, shown.message)
    assertEquals(15, shown.ageSeconds)
    assertNotNull(shown.fix)
    assertFalse(shown.canRecenter)
  }

  @Test
  fun loadingAndFailureRemainDistinctFromAbsentSharing() {
    val loading = orderTrackingPresentation(OrderTrackingState.Loading, now)
    assertTrue(loading.loading)
    assertFalse(loading.canRetry)
    val failed = orderTrackingPresentation(OrderTrackingState.Unavailable, now)
    assertEquals(R.string.order_tracking_error, failed.message)
    assertTrue(failed.canRetry)
    val absent = orderTrackingPresentation(OrderTrackingState.Position(null), now)
    assertEquals(R.string.order_tracking_unavailable, absent.message)
    assertTrue(absent.canRetry)
  }
}
