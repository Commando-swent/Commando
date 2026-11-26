package com.commando.app.ui.trips

// AI assistance: OpenAI Codex.
import com.commando.app.MainDispatcherRule
import com.commando.app.data.repository.*
import com.commando.app.data.repository.RatingError
import com.commando.app.data.repository.RatingResult
import com.commando.app.data.repository.summary
import com.commando.app.model.RatingSummary
import com.commando.app.model.ReliabilitySummary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripRatingViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val ratings = RecordingRatings()
  private val model by lazy { TripRatingViewModel(ratings) }
  private val summary = RatingSummary("owner", 3, 13.0 / 3)

  private fun TripRatingViewModel.assertReliability(
      completed: Int,
      late: Int,
      owner: String = "owner",
  ) {
    assertEquals(
        RatingResult.Success(ReliabilitySummary(owner, completed, late)),
        reliabilityState.value,
    )
  }

  private fun TripRatingViewModel.assertReliabilityError(error: RatingError) {
    assertEquals(RatingResult.Error(error), reliabilityState.value)
  }

  @Test
  fun starsFinishWhileReliabilityIsPendingAndOverlappingReadsAreDeduplicated() = runTest {
    val gate = CompletableDeferred<RatingResult<ReliabilitySummary>>()
    ratings.load = { RatingResult.Success(summary) }
    ratings.loadReliability = { gate.await() }
    model.retryReliability()
    assertTrue(ratings.reliabilityIds.isEmpty())
    model.refreshAll("owner")
    runCurrent()
    model.retryReliability()
    runCurrent()
    assertEquals(listOf("owner"), ratings.reliabilityIds)
    assertEquals(TripRatingUiState.Content(summary), model.uiState.value)
    assertNull(model.reliabilityState.value)
    gate.complete(RatingResult.Success(ReliabilitySummary("owner", 4, 1)))
    runCurrent()
    model.assertReliability(4, 1)
  }

  @Test
  fun reliabilityFinishesWhileStarsArePendingAndStarRetryDoesNotReloadIt() = runTest {
    val gate = CompletableDeferred<RatingResult<RatingSummary>>()
    ratings.load = { gate.await() }
    model.refreshAll("owner")
    runCurrent()
    val reliability = model.reliabilityState.value
    assertEquals(RatingResult.Success(ReliabilitySummary("owner", 0, 0)), reliability)
    gate.complete(RatingResult.Error(RatingError.Unknown))
    runCurrent()
    model.retry()
    runCurrent()
    assertEquals(listOf("owner"), ratings.reliabilityIds)
    assertEquals(reliability, model.reliabilityState.value)
  }

  @Test
  fun reliabilityFailureRetriesWithoutReloadingStars() = runTest {
    ratings.loadReliability = { throw IllegalStateException("offline") }
    model.refreshAll("owner")
    runCurrent()
    model.assertReliabilityError(RatingError.Unknown)
    ratings.loadReliability = { RatingResult.Success(ReliabilitySummary(it, 5, 0)) }
    model.retryReliability()
    assertNull(model.reliabilityState.value)
    runCurrent()
    model.assertReliability(5, 0)
    assertEquals(listOf("owner"), ratings.ids)
  }

  @Test
  fun reliabilityRejectsAnotherOwnersResponse() = runTest {
    ratings.loadReliability = { RatingResult.Success(ReliabilitySummary("other", 5, 0)) }
    model.refreshReliability("owner")
    runCurrent()
    model.assertReliabilityError(RatingError.InvalidData)
  }

  @Test
  fun reliabilityCancellationAllowsRetryWithoutShowingAnError() = runTest {
    ratings.loadReliability = { throw CancellationException() }
    model.refreshReliability("owner")
    runCurrent()
    assertNull(model.reliabilityState.value)
    ratings.loadReliability = null
    model.retryReliability()
    runCurrent()
    model.assertReliability(0, 0)
  }

  @Test
  fun ownerRoundTripCancelsReliabilityAndDiscardsLateResponses() = runTest {
    for (late in
        listOf(
            RatingResult.Success(ReliabilitySummary("owner", 5, 0)),
            RatingResult.Error(RatingError.Unknown),
        )) {
      val gate = CompletableDeferred<RatingResult<ReliabilitySummary>>()
      var cancelled = false
      ratings.loadReliability = {
        try {
          gate.await()
        } catch (_: CancellationException) {
          cancelled = true
          withContext(NonCancellable) { gate.await() }
        }
      }
      model.refreshReliability("owner")
      runCurrent()
      ratings.loadReliability = null
      model.refreshReliability("other")
      assertNull(model.reliabilityState.value)
      runCurrent()
      assertTrue(cancelled)
      model.assertReliability(0, 0, owner = "other")
      model.refreshReliability("owner")
      runCurrent()
      gate.complete(late)
      runCurrent()
      model.assertReliability(0, 0)
    }
  }

  @Test
  fun endedAccountCannotRestoreSummariesAfterReturningToOriginalUser() = runTest {
    for (next in listOf(null, "other-account")) {
      var uid: String? = "requester"
      val changes = MutableSharedFlow<String?>()
      val scoped = TripRatingViewModel(ratings, { uid }, changes)
      val gate = CompletableDeferred<RatingResult<ReliabilitySummary>>()
      ratings.loadReliability = { withContext(NonCancellable) { gate.await() } }
      scoped.refreshAll("owner")
      runCurrent()
      uid = next
      changes.emit(uid)
      runCurrent()
      uid = "requester"
      gate.complete(RatingResult.Success(ReliabilitySummary("owner", 5, 0)))
      runCurrent()
      scoped.refreshAll("owner")
      assertEquals(TripRatingUiState.Error(RatingError.NotAuthenticated), scoped.uiState.value)
      scoped.assertReliabilityError(RatingError.NotAuthenticated)
    }
  }

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
