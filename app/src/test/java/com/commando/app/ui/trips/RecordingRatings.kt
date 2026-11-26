package com.commando.app.ui.trips

// AI assistance: OpenAI Codex.
import com.commando.app.data.repository.*
import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.data.repository.FakeRatingRepository
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.data.repository.RatingRepository
import com.commando.app.data.repository.RatingResult
import com.commando.app.model.RatingSummary
import com.commando.app.model.ReliabilitySummary

/** Records summary lookups and optional responses for ViewModel and navigation tests. */
internal class RecordingRatings(
    identity: () -> String? = { "requester" },
    val ids: MutableList<String> = mutableListOf(),
    private val base: RatingRepository =
        FakeRatingRepository(identity, FakeOrderRepository(identity), FakeTripRepository("owner")),
) : RatingRepository by base {
  var load: (suspend (String) -> RatingResult<RatingSummary>)? = null
  val reliabilityIds = mutableListOf<String>()
  var loadReliability: (suspend (String) -> RatingResult<ReliabilitySummary>)? = null

  override suspend fun getReliabilitySummary(commandoId: String): RatingResult<ReliabilitySummary> {
    reliabilityIds += commandoId
    return loadReliability?.invoke(commandoId) ?: base.getReliabilitySummary(commandoId)
  }

  override suspend fun getRatingSummary(commandoId: String): RatingResult<RatingSummary> {
    ids += commandoId
    return load?.invoke(commandoId) ?: base.getRatingSummary(commandoId)
  }
}
