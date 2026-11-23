package com.commando.app.data.repository

// AI assistance: OpenAI Codex.
import com.commando.app.model.CommandoPosition
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/** Shared in-memory transport for previews/tests; access rules are tested against Firestore. */
class FakeCommandoPositionRepository(
    private val currentUserId: StateFlow<String?>,
    private val clock: Clock = Clock.systemUTC(),
    private val positions: MutableStateFlow<Map<String, CommandoPosition>> =
        MutableStateFlow(emptyMap()),
    private val discoveryTrips: MutableStateFlow<Map<String, String>> =
        MutableStateFlow(emptyMap()),
) : CommandoPositionRepository {
  override suspend fun publish(tripId: String, position: CommandoPosition) {
    val uid = user()
    require(tripId.isDocumentId() && position.isFresh(clock.instant()))
    checkCaptureTime(positions.value[uid], position)
    discoveryTrips.update { it - uid }
    positions.update {
      checkCaptureTime(it[uid], position)
      it + (uid to position)
    }
  }

  override suspend fun publishForDiscovery(tripId: String, position: CommandoPosition) {
    publish(tripId, position)
    discoveryTrips.update { it + (user() to tripId) }
  }

  override fun observeDiscovery(ownerId: String, tripId: String): Flow<CommandoPosition?> =
      observe(ownerId).combine(discoveryTrips) { fix, consent ->
        fix.takeIf { consent[ownerId] == tripId }
      }

  override suspend fun stopSharing() {
    val uid = user()
    discoveryTrips.update { it - uid }
    positions.update { it - uid }
  }

  override fun observe(ownerId: String, orderId: String?): Flow<CommandoPosition?> = flow {
    val uid = user()
    require(ownerId.isDocumentId() && (orderId == null || orderId.isDocumentId()))
    emitAll(
        positions
            .combine(currentUserId) { stored, session ->
              check(session == uid) { "Position session ended" }
              stored[ownerId]
            }
            .freshPositions(clock)
    )
  }

  private fun checkCaptureTime(previous: CommandoPosition?, position: CommandoPosition) {
    require(previous == null || !position.capturedAt.isBefore(previous.capturedAt)) {
      "Position capture time cannot regress"
    }
  }

  private fun user() =
      currentUserId.value?.takeIf { it.isDocumentId() }
          ?: throw SecurityException("Position requires an authenticated session")
}
