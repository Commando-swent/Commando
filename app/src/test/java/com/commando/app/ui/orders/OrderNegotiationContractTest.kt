package com.commando.app.ui.orders

// AI assistance: OpenAI Codex.
import com.commando.app.model.*
import org.junit.Assert.*
import org.junit.Test

class OrderNegotiationContractTest {
  private val initial = NegotiationFixture.snapshot

  @Test
  fun directAcceptanceBelongsToThePersistedOwner() {
    assertTrue(initial.canRespond(initial.trip.ownerId))
    assertFalse(initial.canRespond(initial.order.requesterId))
    assertFalse(initial.canRespond("unrelated"))
  }

  @Test
  fun aCounterofferPassesTheTurnToTheOtherParticipant() {
    val counter =
        initial.copy(offer = initial.offer.copy(revision = 1, proposerId = initial.trip.ownerId))
    assertTrue(counter.canRespond(initial.order.requesterId))
    assertFalse(counter.canRespond(initial.trip.ownerId))
  }

  @Test
  fun aMismatchedTripCannotGrantParticipantAccess() {
    assertFalse(
        initial
            .copy(trip = initial.trip.copy(id = "other-trip"))
            .isParticipant(initial.order.requesterId)
    )
  }

  @Test
  fun checkoutNeverSubstitutesAnUnconfirmedFeeIntoTheOrder() {
    val agreed =
        initial.copy(
            status = OfferStatus.AGREED,
            order = initial.order.copy(status = OrderStatus.ACCEPTED),
        )
    assertEquals(agreed.order, agreed.checkoutOrder)
    assertNull(
        agreed
            .copy(offer = agreed.offer.copy(terms = agreed.offer.terms.copy(feeCents = 450)))
            .checkoutOrder
    )
  }

  @Test
  fun purchasedOrDeclinedOrdersCannotBeNegotiatedAgain() {
    for (status in OrderStatus.entries.filter { it != OrderStatus.PENDING }) {
      assertFalse(
          initial.copy(order = initial.order.copy(status = status)).canRespond(initial.trip.ownerId)
      )
    }
    assertFalse(initial.copy(status = OfferStatus.DECLINED).canRespond(initial.trip.ownerId))
  }

  @Test
  fun malformedProposersAndRevisionsFailClosed() {
    assertFalse(
        initial
            .copy(offer = initial.offer.copy(proposerId = "outsider"))
            .canRespond(initial.trip.ownerId)
    )
    assertFalse(
        initial.copy(offer = initial.offer.copy(revision = -1)).canRespond(initial.trip.ownerId)
    )
  }

  @Test
  fun proposedHandoffMustFollowShoppingAndBeInTheFuture() {
    assertTrue(initial.acceptsTerms(initial.offer.terms, NegotiationFixture.now))
    assertFalse(
        initial.acceptsTerms(
            initial.offer.terms.copy(at = initial.trip.scheduledAt.minusSeconds(1)),
            NegotiationFixture.now,
        )
    )
    assertFalse(initial.acceptsTerms(initial.offer.terms, initial.offer.terms.at))
  }

  @Test
  fun proposedTotalsCannotOverflowTheExistingCentsContract() {
    assertFalse(
        initial.acceptsTerms(
            initial.offer.terms.copy(feeCents = Int.MAX_VALUE),
            NegotiationFixture.now,
        )
    )
  }

  @Test
  fun aBlankMeetingLocationCannotBeAgreed() {
    assertFalse(
        initial.acceptsTerms(initial.offer.terms.copy(location = " "), NegotiationFixture.now)
    )
  }

  @Test
  fun requesterAndCommandoMustBeDifferentPeople() {
    val ownTrip = initial.copy(trip = initial.trip.copy(ownerId = initial.order.requesterId))
    assertFalse(ownTrip.isParticipant(initial.order.requesterId))
  }

  @Test
  fun completedTripCannotReceiveAnotherOfferResponse() {
    assertFalse(
        initial
            .copy(trip = initial.trip.copy(status = TripStatus.COMPLETED))
            .canRespond(initial.trip.ownerId)
    )
  }

  @Test
  fun negativeProductTotalCannotBecomeCheckoutTerms() {
    val invalid =
        initial.copy(
            order =
                initial.order.copy(
                    items = listOf(initial.order.items.first().copy(unitPriceCents = -1))
                )
        )
    assertFalse(
        invalid.acceptsTerms(initial.offer.terms.copy(feeCents = 0), NegotiationFixture.now)
    )
  }

  @Test
  fun agreedTermsDoNotPermitCheckoutUntilTheOrderIsPersistedAsAccepted() {
    assertNull(initial.copy(status = OfferStatus.AGREED).checkoutOrder)
  }

  @Test
  fun negativeFeeCannotBeProposed() {
    assertFalse(
        initial.acceptsTerms(initial.offer.terms.copy(feeCents = -1), NegotiationFixture.now)
    )
  }
}
