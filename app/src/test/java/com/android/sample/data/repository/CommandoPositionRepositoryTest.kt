package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.CommandoPosition
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Deterministic cross-client transport and unchanged-snapshot expiry regressions. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandoPositionRepositoryTest {
  private val start = Instant.parse("2026-11-09T12:00:00Z")
  private val fix = CommandoPosition(46.52, 6.56, 30.0, start)

  @Test
  fun freshnessRejectsInvalidFutureAndBoundaryFixes() {
    assertTrue(fix.isFresh(start))
    assertFalse(fix.isFresh(start.minusMillis(1)))
    assertTrue(fix.isFresh(start.plusMillis(119_999)))
    assertFalse(fix.isFresh(start.plus(CommandoPosition.MAX_AGE)))
    for (bad in
        listOf(
            fix.copy(latitude = Double.NaN),
            fix.copy(latitude = 91.0),
            fix.copy(longitude = -181.0),
            fix.copy(accuracyMeters = -1.0),
            fix.copy(accuracyMeters = Double.POSITIVE_INFINITY),
        )) assertFalse(bad.isFresh(start))
  }

  @Test
  fun observersReceiveReplacementAndRemovalAcrossClients() = runTest {
    val storage = MutableStateFlow<Map<String, CommandoPosition>>(emptyMap())
    val clock = Clock.fixed(start, ZoneOffset.UTC)
    val owner = FakeCommandoPositionRepository({ "owner" }, clock, storage)
    val viewer = FakeCommandoPositionRepository({ "viewer" }, clock, storage)
    val seen = mutableListOf<CommandoPosition?>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      viewer.observe("owner").collect { seen += it }
    }
    owner.publish("trip", fix)
    owner.publish("trip", fix.copy(latitude = 46.53))
    assertEquals(1, storage.value.size)
    owner.stopSharing()
    assertEquals(listOf(null, fix, fix.copy(latitude = 46.53), null), seen)
  }

  @Test
  fun quietPositionExpiresAndReplacementCancelsPreviousExpiry() = runTest {
    val clock =
        object : Clock() {
          override fun getZone(): ZoneId = ZoneOffset.UTC

          override fun withZone(zone: ZoneId): Clock = this

          override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
        }
    val repository = FakeCommandoPositionRepository({ "owner" }, clock)
    val seen = mutableListOf<CommandoPosition?>()
    val collection =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observe("owner").collect { seen += it }
        }
    repository.publish("trip", fix)
    advanceTimeBy(60_000)
    val replacement = fix.copy(capturedAt = clock.instant())
    repository.publish("trip", replacement)
    advanceTimeBy(60_000)
    runCurrent()
    assertEquals(replacement, seen.last())
    advanceTimeBy(60_000)
    runCurrent()
    assertNull(seen.last())
    collection.cancel()
    repository.publish("trip", fix.copy(capturedAt = clock.instant()))
    assertNull(seen.last())
  }

  @Test
  fun invalidPublishDoesNotReplaceExistingPosition() = runTest {
    val storage = MutableStateFlow(mapOf("owner" to fix))
    val repo =
        FakeCommandoPositionRepository({ "owner" }, Clock.fixed(start, ZoneOffset.UTC), storage)
    try {
      repo.publish("trip", fix.copy(accuracyMeters = Double.NaN))
      fail()
    } catch (_: IllegalArgumentException) {
      assertEquals(fix, storage.value["owner"])
    }
  }

  @Test
  fun signedOutOperationsAreRejected() = runTest {
    val repo = FakeCommandoPositionRepository({ null })
    try {
      repo.stopSharing()
      fail()
    } catch (_: SecurityException) {
      /* expected */
    }
    try {
      repo.publish("trip", fix)
      fail()
    } catch (_: SecurityException) {
      /* expected */
    }
    try {
      repo.observe("owner").first()
      fail()
    } catch (_: SecurityException) {
      /* expected */
    }
  }
}
