package com.commando.app.ui.trips

// AI assistance: OpenAI Codex.
import com.android.sample.MainDispatcherRule
import com.android.sample.data.repository.*
import com.commando.app.data.repository.RatingError
import com.commando.app.data.repository.RatingResult
import com.commando.app.data.repository.summary
import com.commando.app.model.RatingSummary
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripRatingViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val ratings = RecordingRatings()
  private val model = TripRatingViewModel(ratings)
  private val summary = RatingSummary("owner", 3, 13.0 / 3)

  @Test
  fun explicitLoadPreservesOwnerAverageAndCountAndRefreshesAfterCompletion() = runTest {
    model.retry()
    runCurrent()
    assertEquals(TripRatingUiState.Loading, model.uiState.value)
    assertTrue(ratings.ids.isEmpty())
    val gate = CompletableDeferred<RatingResult<RatingSummary>>()
    ratings.load = { gate.await() }
    model.refresh(summary.commandoId)
    model.refresh(summary.commandoId)
    model.retry()
    runCurrent()
    model.retry()
    assertEquals(listOf("owner"), ratings.ids)
    assertEquals(TripRatingUiState.Loading, model.uiState.value)
    gate.complete(RatingResult.Success(summary))
    runCurrent()
    assertEquals(TripRatingUiState.Content(summary), model.uiState.value)
    ratings.load = { RatingResult.Success(summary.copy(ratingCount = 4, averageScore = 4.5)) }
    model.refresh("owner")
    assertEquals(TripRatingUiState.Loading, model.uiState.value)
    runCurrent()
    assertEquals(listOf("owner", "owner"), ratings.ids)
    assertEquals(4.5, (model.uiState.value as TripRatingUiState.Content).summary.averageScore)
    assertEquals(4, (model.uiState.value as TripRatingUiState.Content).summary.ratingCount)
  }

  @Test
  fun emptyErrorsAndUnexpectedFailuresCanRetry() = runTest {
    for (error in
        listOf(
            RatingError.NotAuthenticated,
            RatingError.PermissionDenied,
            RatingError.NotFound,
            RatingError.InvalidData,
            RatingError.Unknown,
        )) {
      ratings.load = { RatingResult.Error(error) }
      model.refresh("owner")
      runCurrent()
      assertEquals(TripRatingUiState.Error(error), model.uiState.value)
    }
    ratings.load = { throw IllegalStateException("offline") }
    model.retry()
    runCurrent()
    assertEquals(TripRatingUiState.Error(RatingError.Unknown), model.uiState.value)
    ratings.load = { RatingResult.Success(RatingSummary(it, 0, null)) }
    model.retry()
    runCurrent()
    assertEquals(TripRatingUiState.Empty, model.uiState.value)
    assertTrue(ratings.ids.all { it == "owner" })
    ratings.load = { RatingResult.Success(summary.copy(commandoId = "wrong-owner")) }
    model.retry()
    runCurrent()
    assertEquals(TripRatingUiState.Error(RatingError.InvalidData), model.uiState.value)
  }

  @Test
  fun cancellationIsNotAnErrorAndAllowsRetry() = runTest {
    ratings.load = { throw CancellationException("cancelled") }
    model.refresh("owner")
    runCurrent()
    assertEquals(TripRatingUiState.Loading, model.uiState.value)
    ratings.load = { RatingResult.Success(summary) }
    model.retry()
    runCurrent()
    assertEquals(listOf("owner", "owner"), ratings.ids)
    assertEquals(TripRatingUiState.Content(summary), model.uiState.value)
  }

  @Test
  fun ownerChangesCancelWorkAndRejectLateSuccessAndErrorEvenWhenReturningToSameOwner() = runTest {
    for (late in
        listOf(
            RatingResult.Success(summary.copy(averageScore = 1.0)),
            RatingResult.Error(RatingError.Unknown),
        )) {
      val gate = CompletableDeferred<RatingResult<RatingSummary>>()
      var cancelled = false
      var returned = false
      ratings.load = {
        val result =
            try {
              gate.await()
            } catch (_: CancellationException) {
              cancelled = true
              withContext(NonCancellable) { gate.await() }
            }
        returned = true
        result
      }
      model.refresh("owner")
      runCurrent()
      ratings.load = { RatingResult.Success(summary.copy(commandoId = it)) }
      model.refresh("other-owner")
      assertEquals(TripRatingUiState.Loading, model.uiState.value)
      runCurrent()
      assertTrue(cancelled)
      assertEquals(
          "other-owner",
          (model.uiState.value as TripRatingUiState.Content).summary.commandoId,
      )
      model.refresh("owner")
      runCurrent()
      gate.complete(late)
      runCurrent()
      assertTrue(returned)
      assertEquals(TripRatingUiState.Content(summary), model.uiState.value)
    }
  }
}
