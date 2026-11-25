package com.commando.app.model

// AI assistance: Claude Code.
import java.time.Duration
import java.time.Instant

/** Time after acceptance during which the trip owner may cancel an order without a penalty. */
val COMMANDO_CANCELLATION_GRACE: Duration = Duration.ofMinutes(10)

/**
 * True when a trip owner cancelled an accepted order strictly within [COMMANDO_CANCELLATION_GRACE]
 * after accepting it, so the reliability score (#387) must not penalize it.
 *
 * Both times must be server times, i.e. the `occurredAt` of the `ORDER_ACCEPTED` and
 * `ORDER_CANCELLED` events, never the device clock.
 *
 * @param acceptedAt Time the order was accepted.
 * @param cancelledAt Time the order was cancelled; must not be before [acceptedAt].
 * @throws IllegalArgumentException When [cancelledAt] is before [acceptedAt].
 */
fun isPenaltyFreeCancellation(acceptedAt: Instant, cancelledAt: Instant): Boolean {
  require(!cancelledAt.isBefore(acceptedAt)) { "An order cannot be cancelled before acceptance" }
  return cancelledAt.isBefore(acceptedAt.plus(COMMANDO_CANCELLATION_GRACE))
}
