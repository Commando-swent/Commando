package com.android.sample.ui.trips

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.*
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.model.RatingSummary

/** Records summary lookups and optional responses for ViewModel and navigation tests. */
internal class RecordingRatings(
    identity: () -> String? = { "requester" },
    val ids: MutableList<String> = mutableListOf(),
    private val base: RatingRepository =
        FakeRatingRepository(identity, FakeOrderRepository(identity), FakeTripRepository("owner")),
) : RatingRepository by base {
  var load: (suspend (String) -> RatingResult<RatingSummary>)? = null

  override suspend fun getRatingSummary(commandoId: String): RatingResult<RatingSummary> {
    ids += commandoId
    return load?.invoke(commandoId) ?: base.getRatingSummary(commandoId)
  }
}
