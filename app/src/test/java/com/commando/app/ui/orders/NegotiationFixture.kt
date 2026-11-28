package com.commando.app.ui.orders

// AI assistance: OpenAI Codex.
import com.commando.app.model.*
import java.time.Instant

internal object NegotiationFixture {
  val now: Instant = Instant.parse("2026-11-27T12:00:00Z")
  val trip = OrderPaymentFixture.trip.copy(scheduledAt = now.plusSeconds(3600))
  val order = OrderPaymentFixture.order.copy(status = OrderStatus.PENDING, commissionCents = 300)
  val terms = OrderTerms(300, trip.handoffLocation.name, now.plusSeconds(7200))
  val snapshot =
      OrderNegotiation(order, trip, OrderOffer(0, order.requesterId, terms), OfferStatus.OPEN)
}
