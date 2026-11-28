package com.commando.app.model

// AI assistance: OpenAI Codex.
import java.time.Instant

/** CHF cents and the handoff agreed for this order, independent of the trip shopping time. */
data class OrderTerms(val feeCents: Int, val location: String, val at: Instant) {
  fun isValid(now: Instant): Boolean = feeCents >= 0 && location.isValidChatText() && at > now
}

/**
 * Revision zero represents the requester’s original order terms; each counteroffer increments it.
 */
data class OrderOffer(val revision: Int, val proposerId: String, val terms: OrderTerms)

enum class OfferStatus {
  OPEN,
  AGREED,
  DECLINED,
}

/** One server-confirmed revision, including the authoritative order used by checkout. */
data class OrderNegotiation(
    val order: Order,
    val trip: Trip,
    val offer: OrderOffer,
    val status: OfferStatus,
) {
  val nextActorId: String
    get() = if (offer.proposerId == order.requesterId) trip.ownerId else order.requesterId

  fun isParticipant(userId: String): Boolean =
      order.tripId == trip.id &&
          order.requesterId != trip.ownerId &&
          (userId == order.requesterId || userId == trip.ownerId)

  fun canRespond(userId: String): Boolean =
      isParticipant(userId) &&
          offer.revision >= 0 &&
          (offer.proposerId == order.requesterId || offer.proposerId == trip.ownerId) &&
          nextActorId == userId &&
          status == OfferStatus.OPEN &&
          order.status == OrderStatus.PENDING &&
          trip.status == TripStatus.PUBLISHED

  fun acceptsTerms(terms: OrderTerms, now: Instant): Boolean =
      terms.isValid(now) &&
          terms.at >= trip.scheduledAt &&
          order.totalCents - order.commissionCents + terms.feeCents in 0..Int.MAX_VALUE.toLong()

  /** Never rewrite a local fee into checkout: acceptance must already be reflected in the order. */
  val checkoutOrder: Order?
    get() = order.takeIf {
      status == OfferStatus.AGREED &&
          it.status == OrderStatus.ACCEPTED &&
          it.commissionCents == offer.terms.feeCents
    }
}
