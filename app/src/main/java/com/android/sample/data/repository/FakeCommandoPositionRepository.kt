package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.CommandoPosition
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Shared in-memory transport for previews/tests; access rules are tested against Firestore. */
class FakeCommandoPositionRepository(
    private val currentUserId: () -> String?,
    private val clock: Clock = Clock.systemUTC(),
    private val positions: MutableStateFlow<Map<String, CommandoPosition>> =
        MutableStateFlow(emptyMap()),
) : CommandoPositionRepository {
  override suspend fun publish(tripId: String, position: CommandoPosition) {
    val uid = user()
    require(tripId.isDocumentId() && position.isFresh(clock.instant()))
    positions.update { it + (uid to position) }
  }

  override suspend fun stopSharing() {
    val uid = user()
    positions.update { it - uid }
  }

  override fun observe(ownerId: String, orderId: String?): Flow<CommandoPosition?> = flow {
    val uid = user()
    require(ownerId.isDocumentId() && (orderId == null || orderId.isDocumentId()))
    emitAll(
        positions
            .map {
              check(currentUserId() == uid) { "Position session ended" }
              it[ownerId]
            }
            .freshPositions(clock)
    )
  }

  private fun user() =
      currentUserId()?.takeIf { it.isDocumentId() }
          ?: throw SecurityException("Position requires an authenticated session")
}
