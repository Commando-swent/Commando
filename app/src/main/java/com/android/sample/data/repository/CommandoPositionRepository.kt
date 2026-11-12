package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.CommandoPosition
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/** One shared position per commando; consumers must cancel observation when their session ends. */
interface CommandoPositionRepository {
  /** Replaces the signed-in owner's position. [tripId] identifies an owned eligible trip. */
  suspend fun publish(tripId: String, position: CommandoPosition)

  /** Deletes the owner's position; call and await before signing out while still authenticated. */
  suspend fun stopSharing()

  /**
   * Observes one owner, never a collection scan. Supply an active [orderId] for requester tracking.
   * Null means absent/unconfirmed/expired; authorization and transport failures terminate the flow.
   */
  fun observe(ownerId: String, orderId: String? = null): Flow<CommandoPosition?>
}

/** Expires an unchanged snapshot too; a quiet stream must not leave a stale marker visible. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun Flow<CommandoPosition?>.freshPositions(clock: Clock): Flow<CommandoPosition?> =
    transformLatest { position ->
      val now = clock.instant()
      val fresh = position?.takeIf { it.isFresh(now) }
      emit(fresh)
      if (fresh != null) {
        delay(Duration.between(now, fresh.capturedAt.plus(CommandoPosition.MAX_AGE)).toMillis())
        emit(null)
      }
    }
