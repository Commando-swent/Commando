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
            fix.copy(accuracyMeters = CommandoPosition.MAX_ACCURACY_METERS + 1),
            fix.copy(accuracyMeters = Double.POSITIVE_INFINITY),
        )) assertFalse(bad.isFresh(start))
  }

  @Test
  fun observersReceiveReplacementAndRemovalAcrossClients() = runTest {
    val storage = MutableStateFlow<Map<String, CommandoPosition>>(emptyMap())
    val clock = Clock.fixed(start, ZoneOffset.UTC)
    val owner = FakeCommandoPositionRepository(MutableStateFlow<String?>("owner"), clock, storage)
    val viewer = FakeCommandoPositionRepository(MutableStateFlow<String?>("viewer"), clock, storage)
    val seen = mutableListOf<CommandoPosition?>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      viewer.observe("owner").collect { seen += it }
    }
    runCurrent()
    owner.publish("trip", fix)
    runCurrent()
    owner.publish("trip", fix.copy(latitude = 46.53))
    runCurrent()
    assertEquals(1, storage.value.size)
    owner.stopSharing()
    runCurrent()
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
    val repository = FakeCommandoPositionRepository(MutableStateFlow<String?>("owner"), clock)
    val seen = mutableListOf<CommandoPosition?>()
    val collection =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observe("owner").collect { seen += it }
        }
    repository.publish("trip", fix)
    advanceTimeBy(60_000)
    val replacement = fix.copy(capturedAt = clock.instant())
    repository.publish("trip", replacement)
    runCurrent()
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
    val repo = repository(storage)
    rejects<IllegalArgumentException> {
      repo.publish("trip", fix.copy(accuracyMeters = Double.NaN))
    }
    assertEquals(fix, storage.value["owner"])
  }

  @Test
  fun signedOutOperationsAreRejected() = runTest {
    val repo = FakeCommandoPositionRepository(MutableStateFlow<String?>(null))
    rejects<SecurityException> { repo.stopSharing() }
    rejects<SecurityException> { repo.publish("trip", fix) }
    rejects<SecurityException> { repo.observe("owner").first() }
  }

  @Test
  fun invalidReferencesNeverPublishOrStartObservation() = runTest {
    val repo = repository()
    for (id in listOf("", "a/b", ".", "..")) {
      rejects<IllegalArgumentException> { repo.publish(id, fix) }
      rejects<IllegalArgumentException> { repo.observe(id).first() }
      rejects<IllegalArgumentException> { repo.observe("owner", id).first() }
    }
    assertNull(repo.observe("owner", "order").first())
  }

  @Test
  fun staleOrInvalidStoredPositionsAreNeverExposedAsLive() = runTest {
    for (position in
        listOf(
            fix.copy(capturedAt = start.minusSeconds(120)),
            fix.copy(capturedAt = start.plusSeconds(1)),
            fix.copy(latitude = Double.NaN),
        )) {
      val repo = repository(MutableStateFlow(mapOf("owner" to position)))
      assertNull(repo.observe("owner").first())
    }
  }

  @Test
  fun sessionEndCancelsQuietObservationWithoutAnotherPositionWrite() = runTest {
    for (nextSession in listOf(null, "another")) {
      val identity = MutableStateFlow<String?>("owner")
      val storage = MutableStateFlow(mapOf("owner" to fix))
      val repo =
          FakeCommandoPositionRepository(identity, Clock.fixed(start, ZoneOffset.UTC), storage)
      val rejected = kotlinx.coroutines.CompletableDeferred<Throwable>()
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        try {
          repo.observe("owner").collect()
        } catch (error: IllegalStateException) {
          rejected.complete(error)
        }
      }
      runCurrent()
      assertEquals(1, storage.subscriptionCount.value)
      identity.value = nextSession
      runCurrent()
      assertTrue("Session change must close a quiet observation", rejected.isCompleted)
      assertEquals("Position session ended", rejected.await().message)
      assertEquals(mapOf("owner" to fix), storage.value)
      assertEquals(0, storage.subscriptionCount.value)
      assertEquals(0, identity.subscriptionCount.value)
    }
  }

  @Test
  fun regressingFixIsRejectedAtomicallyWithoutReplacingStoredPosition() = runTest {
    val storage = MutableStateFlow(mapOf("owner" to fix))
    val repo = repository(storage)
    rejects<IllegalArgumentException> {
      repo.publish("trip", fix.copy(capturedAt = start.minusSeconds(1)))
    }
    assertEquals(fix, storage.value["owner"])
    repo.publish("trip", fix.copy(latitude = 46.53))
    assertEquals(46.53, storage.value.getValue("owner").latitude, 0.0)
  }

  private fun repository(
      storage: MutableStateFlow<Map<String, CommandoPosition>> = MutableStateFlow(emptyMap()),
  ) =
      FakeCommandoPositionRepository(
          MutableStateFlow("owner"),
          Clock.fixed(start, ZoneOffset.UTC),
          storage,
      )

  private suspend inline fun <reified T : Exception> rejects(action: () -> Unit) {
    try {
      action()
      fail("Expected ${T::class.simpleName}")
    } catch (error: Exception) {
      if (error !is T) throw error
    }
  }
}
