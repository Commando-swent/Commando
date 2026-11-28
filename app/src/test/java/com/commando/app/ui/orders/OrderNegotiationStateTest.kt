package com.commando.app.ui.orders

// AI assistance: OpenAI Codex.
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Permission presentation must remain safe before a screen or ViewModel is attached. */
class OrderNegotiationStateTest {
  private val snapshot = NegotiationFixture.snapshot
  private val ready = OrderNegotiationState.Content(snapshot, snapshot.trip.ownerId)

  @Test
  fun nextParticipantCanRespondToConfirmedTerms() {
    assertTrue(ready.canAct)
  }

  @Test
  fun proposerCannotRespondToTheirOwnOffer() {
    assertFalse(ready.copy(viewerId = snapshot.offer.proposerId).canAct)
  }

  @Test
  fun offlineStateDisablesNewActions() {
    assertFalse(ready.copy(online = false).canAct)
  }

  @Test
  fun savingStatePreventsAnotherSubmission() {
    assertFalse(ready.copy(busy = true).canAct)
  }

  @Test
  fun uncertainOutcomeBlocksANewOperation() {
    assertFalse(ready.copy(notice = NegotiationNotice.UNCERTAIN).canAct)
  }

  @Test
  fun definiteFailureDoesNotLockOutACorrectedProposal() {
    assertTrue(ready.copy(notice = NegotiationNotice.FAILED).canAct)
  }
}
