package com.android.sample.model

// AI assistance: Claude Code.
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TripSearchTest {
  private val zurich = ZoneId.of("Europe/Zurich")
  /** 12:00 on 2026-06-15 in Zurich (UTC+2). */
  private val now = Instant.parse("2026-06-15T10:00:00Z")
  private val places = TripLocations.handoffPoints.associateBy { it.name }

  private fun place(name: String) = places.getValue(name)

  private val bc = place("BC building")

  private fun local(text: String, zone: ZoneId = zurich) =
      LocalDateTime.parse(text).atZone(zone).toInstant()

  private fun trip(
      id: String,
      at: Instant = now,
      store: String = "Migros EPFL",
      handoff: Location = bc,
  ) =
      Trip(
          id = id,
          ownerId = "owner",
          store = Location(store, 46.5229222, 6.5656983),
          scheduledAt = at,
          handoffLocation = handoff,
          status = TripStatus.PUBLISHED,
          createdAt = now,
          updatedAt = now,
      )

  /** Same helper as TripLocationFilterTest: [meters] due north of [from]. */
  private fun north(from: Location, meters: Double) =
      Location("offset", from.latitude + Math.toDegrees(meters / 6_371_008.8), from.longitude)

  private fun ids(
      trips: List<Trip>,
      criteria: TripSearchCriteria = TripSearchCriteria(),
      point: Location? = null,
      at: Instant = now,
      zone: ZoneId = zurich,
  ) = searchTrips(trips, point, criteria, at, zone).map { it.id }

  private fun period(p: DeparturePeriod) = TripSearchCriteria(period = p)

  private fun custom(start: String, end: String) =
      DeparturePeriod.Custom(LocalDate.parse(start), LocalDate.parse(end))

  // --- Store filter ---

  @Test
  fun storeFilterMatchesWholeWordChainCaseInsensitively() {
    val stores =
        listOf(
            "Migros EPFL",
            "Coop Ecublens",
            "MIGROS",
            "coop-pronto",
            "Denner EPFL",
            "Corner shop",
            "Migros Coop",
            "Migrosette",
        )
    val trips = stores.mapIndexed { i, s -> trip(s, now.plusSeconds(i.toLong()), store = s) }
    assertEquals(stores, ids(trips))
    assertEquals(stores, ids(trips, TripSearchCriteria(store = StoreFilter.ALL)))
    assertEquals(
        listOf("Migros EPFL", "MIGROS"),
        ids(trips, TripSearchCriteria(store = StoreFilter.MIGROS)),
    )
    assertEquals(
        listOf("Coop Ecublens", "coop-pronto"),
        ids(trips, TripSearchCriteria(store = StoreFilter.COOP)),
    )
  }

  // --- Departure periods ---

  @Test
  fun todayAndTomorrowAreHalfOpenLocalDays() {
    val trips =
        listOf(
            trip("yesterdayLast", local("2026-06-14T23:59:59.999")),
            trip("todayStart", local("2026-06-15T00:00")),
            trip("todayLast", local("2026-06-15T23:59:59.999")),
            trip("tomorrowStart", local("2026-06-16T00:00")),
            trip("tomorrowLast", local("2026-06-16T23:59:59.999")),
            trip("dayAfterStart", local("2026-06-17T00:00")),
        )
    assertEquals(listOf("todayStart", "todayLast"), ids(trips, period(DeparturePeriod.Today)))
    assertEquals(
        listOf("tomorrowStart", "tomorrowLast"),
        ids(trips, period(DeparturePeriod.Tomorrow)),
    )
    assertEquals(trips.map { it.id }, ids(trips, period(DeparturePeriod.AnyTime)))
  }

  @Test
  fun customRangeIncludesBothWholeDays() {
    val trips =
        listOf(
            trip("before", local("2026-06-19T23:59:59.999")),
            trip("first", local("2026-06-20T00:00")),
            trip("middle", local("2026-06-21T12:00")),
            trip("last", local("2026-06-22T23:59:59.999")),
            trip("after", local("2026-06-23T00:00")),
        )
    assertEquals(
        listOf("first", "middle", "last"),
        ids(trips, period(custom("2026-06-20", "2026-06-22"))),
    )
    assertEquals(listOf("middle"), ids(trips, period(custom("2026-06-21", "2026-06-21"))))
    assertEquals(emptyList<String>(), ids(trips, period(custom("2026-06-22", "2026-06-20"))))
  }

  @Test
  fun todayDependsOnTheZone() {
    // 23:30Z on 15 June: 16 June 01:30 in Zurich, 15 June 16:30 in LA, 16 June 11:30 in Auckland.
    val at = Instant.parse("2026-06-15T23:30:00Z")
    val trips =
        listOf(
            trip("a", Instant.parse("2026-06-15T20:00:00Z")),
            trip("b", Instant.parse("2026-06-16T08:00:00Z")),
        )
    val today = period(DeparturePeriod.Today)
    assertEquals(listOf("b"), ids(trips, today, at = at, zone = zurich))
    assertEquals(listOf("a"), ids(trips, today, at = at, zone = ZoneId.of("America/Los_Angeles")))
    assertEquals(listOf("a", "b"), ids(trips, today, at = at, zone = ZoneId.of("Pacific/Auckland")))
  }

  @Test
  fun dayWindowsFollowDaylightSavingTransitions() {
    val spring = Instant.parse("2026-03-29T10:00:00Z")
    val springWindow = DeparturePeriod.Today.window(spring, zurich)!!
    assertEquals(Duration.ofHours(23), Duration.between(springWindow.from, springWindow.until))
    val springTrips =
        listOf(
            trip("before", Instant.parse("2026-03-28T22:59:59Z")),
            trip("start", Instant.parse("2026-03-28T23:00:00Z")),
            trip("last", Instant.parse("2026-03-29T21:59:59Z")),
            trip("next", Instant.parse("2026-03-29T22:00:00Z")),
        )
    assertEquals(
        listOf("start", "last"),
        ids(springTrips, period(DeparturePeriod.Today), at = spring),
    )

    val fall = Instant.parse("2026-10-25T10:00:00Z")
    val fallWindow = DeparturePeriod.Today.window(fall, zurich)!!
    assertEquals(Duration.ofHours(25), Duration.between(fallWindow.from, fallWindow.until))
    val fallTrips =
        listOf(
            trip("start", Instant.parse("2026-10-24T22:00:00Z")),
            trip("lateEvening", Instant.parse("2026-10-25T22:30:00Z")), // 23:30 local, hour 25
            trip("next", Instant.parse("2026-10-25T23:00:00Z")),
        )
    assertEquals(
        listOf("start", "lateEvening"),
        ids(fallTrips, period(DeparturePeriod.Today), at = fall),
    )
    // Tomorrow seen from the day before each transition spans the short/long day too.
    val beforeSpring =
        DeparturePeriod.Tomorrow.window(Instant.parse("2026-03-28T10:00:00Z"), zurich)!!
    assertEquals(springWindow, beforeSpring)
    assertEquals(
        fallWindow,
        custom("2026-10-25", "2026-10-25").window(Instant.parse("2026-01-01T00:00:00Z"), zurich),
    )
    assertNull(DeparturePeriod.AnyTime.window(now, zurich))
  }

  // --- Criteria problems ---

  @Test
  fun criteriaProblemsForCustomRanges() {
    fun problem(p: DeparturePeriod, at: Instant = now) = criteriaProblem(period(p), at, zurich)
    assertNull(problem(DeparturePeriod.AnyTime))
    assertNull(problem(DeparturePeriod.Today))
    assertNull(problem(DeparturePeriod.Tomorrow))
    assertEquals(CriteriaProblem.INVALID_RANGE, problem(custom("2026-06-20", "2026-06-19")))
    // A reversed range that is also in the past is reported as invalid, not expired.
    assertEquals(CriteriaProblem.INVALID_RANGE, problem(custom("2026-06-10", "2026-06-05")))
    assertEquals(CriteriaProblem.EXPIRED_RANGE, problem(custom("2026-06-10", "2026-06-14")))
    // The range ends at Zurich midnight on 15 June = 2026-06-14T22:00Z; exactly then it expired.
    val end = Instant.parse("2026-06-14T22:00:00Z")
    assertEquals(CriteriaProblem.EXPIRED_RANGE, problem(custom("2026-06-10", "2026-06-14"), end))
    assertNull(problem(custom("2026-06-10", "2026-06-14"), end.minusNanos(1)))
    assertNull(problem(custom("2026-06-10", "2026-06-15")))
    assertNull(problem(custom("2026-06-15", "2026-06-15")))
    assertNull(problem(custom("2026-06-20", "2026-06-20")))
  }

  // --- Combined filters ---

  // In repository order (earliest departure first), as getUpcomingTrips returns them.
  private val mixed =
      listOf(
          trip("mTodayNear", local("2026-06-15T14:00"), "Migros EPFL", bc),
          trip("cTodayNear", local("2026-06-15T15:00"), "Coop Ecublens", bc),
          trip("mTodayFar", local("2026-06-15T16:00"), "Migros EPFL", place("CE building")),
          trip("mTomorrowNear", local("2026-06-16T09:00"), "Migros EPFL", place("INM building")),
          trip("cTomorrowFar", local("2026-06-16T10:00"), "Coop Ecublens", place("CE building")),
      )

  @Test
  fun combinedFiltersIntersect() {
    val migros = TripSearchCriteria(store = StoreFilter.MIGROS)
    val today = DeparturePeriod.Today
    assertEquals(listOf("mTodayNear", "mTodayFar", "mTomorrowNear"), ids(mixed, migros))
    assertEquals(listOf("mTodayNear", "mTodayFar"), ids(mixed, migros.copy(period = today)))
    assertEquals(listOf("mTodayNear", "mTomorrowNear"), ids(mixed, migros, point = bc))
    assertEquals(listOf("mTodayNear", "cTodayNear"), ids(mixed, period(today), point = bc))
    assertEquals(listOf("mTodayNear"), ids(mixed, migros.copy(period = today), point = bc))
    assertEquals(
        emptyList<String>(),
        ids(
            mixed,
            TripSearchCriteria(store = StoreFilter.COOP, period = DeparturePeriod.Tomorrow),
            point = bc,
        ),
    )
  }

  @Test
  fun locationRuleIsTheSharedInclusiveRadius() {
    val trips =
        listOf(
            trip("outside", now, handoff = north(bc, HANDOFF_RADIUS_METERS + 0.5)),
            trip("inside", now.plusSeconds(1), handoff = north(bc, HANDOFF_RADIUS_METERS - 0.5)),
        )
    assertEquals(listOf("inside"), ids(trips, point = bc))
    assertEquals(listOf("outside", "inside"), ids(trips))
    assertEquals(emptyList<String>(), ids(trips, point = Location("bad", Double.NaN, 6.56)))
    assertEquals(
        filterTripsServing(mixed, bc).sortedBy { it.scheduledAt }.map { it.id },
        ids(mixed, point = bc),
    )
  }

  // --- Sorting ---

  @Test
  fun earliestDepartureKeepsTheRepositoryOrderAndList() {
    // The repository owns ordering; even an out-of-order list is passed through untouched.
    val trips =
        listOf(
            trip("late", now.plusSeconds(300)),
            trip("tieB", now.plusSeconds(100)),
            trip("early", now),
            trip("tieA", now.plusSeconds(100)),
        )
    assertEquals(listOf("late", "tieB", "early", "tieA"), ids(trips))
    assertSame(trips, searchTrips(trips, null, TripSearchCriteria(), now, zurich))
    // Filtering keeps the remaining trips in repository order.
    val migros = TripSearchCriteria(store = StoreFilter.MIGROS)
    assertEquals(listOf("late", "tieB", "early", "tieA"), ids(trips, migros))
  }

  @Test
  fun distanceSortUsesDistanceThenDepartureThenId() {
    val byDistance = TripSearchCriteria(sort = TripSort.DISTANCE)
    // From BC: BC 0 m, INM 95 m, SV 202 m, CO 280 m; CE 689 m is filtered out.
    val trips =
        listOf(
            trip("co", now, handoff = place("CO building")),
            trip("ce", now, handoff = place("CE building")),
            trip("inmLate", now.plusSeconds(60), handoff = place("INM building")),
            trip("sv", now.plusSeconds(10), handoff = place("SV building")),
            trip("z", now.plusSeconds(30), handoff = bc),
            trip("inmEarly", now.plusSeconds(20), handoff = place("INM building")),
            trip("a", now.plusSeconds(30), handoff = bc),
        )
    assertEquals(
        listOf("a", "z", "inmEarly", "inmLate", "sv", "co"),
        ids(trips, byDistance, point = bc),
    )
    // Without a point, distance sort keeps repository order and nothing is filtered.
    assertEquals(
        listOf("co", "ce", "inmLate", "sv", "z", "inmEarly", "a"),
        ids(trips, byDistance),
    )
    assertEquals(
        emptyList<String>(),
        ids(trips, byDistance, point = Location("bad", 91.0, 6.56)),
    )
  }

  @Test
  fun resultsAreDeterministicAndEmptyInputGivesEmptyOutput() {
    val criteria =
        listOf(
            TripSearchCriteria(),
            TripSearchCriteria(StoreFilter.MIGROS, DeparturePeriod.Today, TripSort.DISTANCE),
            TripSearchCriteria(
                period = custom("2026-06-15", "2026-06-16"),
                sort = TripSort.DISTANCE,
            ),
        )
    for (c in criteria) {
      assertEquals(c.toString(), ids(mixed, c, point = bc), ids(mixed, c, point = bc))
      // Only distance sorting re-orders, and its total order ignores the input order.
      if (c.sort == TripSort.DISTANCE) {
        assertEquals(c.toString(), ids(mixed, c, point = bc), ids(mixed.reversed(), c, point = bc))
      }
      assertEquals(emptyList<String>(), ids(emptyList(), c, point = bc))
      assertEquals(emptyList<String>(), ids(emptyList(), c))
    }
  }

  // --- isActive ---

  @Test
  fun onlyStoreAndPeriodFiltersAreActive() {
    assertFalse(TripSearchCriteria().isActive)
    assertFalse(TripSearchCriteria(StoreFilter.ALL, DeparturePeriod.AnyTime).isActive)
    assertTrue(TripSearchCriteria(store = StoreFilter.COOP).isActive)
    assertTrue(TripSearchCriteria(period = DeparturePeriod.Today).isActive)
    assertTrue(TripSearchCriteria(period = custom("2026-06-15", "2026-06-15")).isActive)
    // Sort is a display preference: changing it alone leaves nothing to clear.
    assertFalse(TripSearchCriteria(sort = TripSort.DISTANCE).isActive)
    assertTrue(TripSearchCriteria(store = StoreFilter.MIGROS, sort = TripSort.DISTANCE).isActive)
  }

  // --- classifyTripSearch ---

  private fun classify(
      trips: List<Trip>,
      criteria: TripSearchCriteria = TripSearchCriteria(),
      point: Location? = null,
  ) = classifyTripSearch(trips, point, criteria, now, zurich)

  private val inverted = period(custom("2026-06-20", "2026-06-18"))
  private val expired = period(custom("2026-06-10", "2026-06-14"))

  @Test
  fun emptySourceIsEmptyEvenWithInvalidOrExpiredCriteria() {
    for (criteria in listOf(TripSearchCriteria(), inverted, expired)) {
      assertEquals(TripFeed.Empty, classify(emptyList(), criteria))
      assertEquals(TripFeed.Empty, classify(emptyList(), criteria, point = bc))
    }
  }

  @Test
  fun invalidAndExpiredRangesAreInvalidCriteriaBeforeMatching() {
    assertEquals(TripFeed.InvalidCriteria(CriteriaProblem.INVALID_RANGE), classify(mixed, inverted))
    assertEquals(TripFeed.InvalidCriteria(CriteriaProblem.EXPIRED_RANGE), classify(mixed, expired))
    // Even when the point alone would leave nothing, the criteria problem is reported.
    val far = Location("Lausanne gare", 46.5167, 6.6291)
    assertEquals(
        TripFeed.InvalidCriteria(CriteriaProblem.INVALID_RANGE),
        classify(mixed, inverted.copy(store = StoreFilter.COOP), far),
    )
    assertEquals(
        TripFeed.InvalidCriteria(CriteriaProblem.EXPIRED_RANGE),
        classify(mixed, expired.copy(sort = TripSort.DISTANCE), bc),
    )
  }

  @Test
  fun noMatchesCarriesThePointOnlyWhenCriteriaAreInactive() {
    val far = Location("Lausanne gare", 46.5167, 6.6291)
    assertEquals(TripFeed.NoMatches(far), classify(mixed, point = far))
    assertEquals(
        TripFeed.NoMatches(far),
        classify(mixed, TripSearchCriteria(sort = TripSort.DISTANCE), far),
    )
    assertEquals(
        TripFeed.NoMatches(null),
        classify(mixed, TripSearchCriteria(store = StoreFilter.MIGROS), far),
    )
    assertEquals(
        TripFeed.NoMatches(null),
        classify(mixed, TripSearchCriteria(StoreFilter.COOP, DeparturePeriod.Tomorrow), bc),
    )
    assertEquals(
        TripFeed.NoMatches(null),
        classify(mixed, period(custom("2026-07-01", "2026-07-02"))),
    )
  }

  @Test
  fun matchesListsTheSearchResultInOrder() {
    assertEquals(TripFeed.Matches(mixed), classify(mixed))
    assertEquals(
        listOf("mTodayNear", "mTomorrowNear"),
        (classify(mixed, TripSearchCriteria(store = StoreFilter.MIGROS), bc) as TripFeed.Matches)
            .trips
            .map { it.id },
    )
    assertEquals(
        listOf("mTodayNear", "cTodayNear", "mTomorrowNear"),
        (classify(mixed, TripSearchCriteria(sort = TripSort.DISTANCE), bc) as TripFeed.Matches)
            .trips
            .map { it.id },
    )
    // A custom range still running today is valid and filters by day.
    assertEquals(
        listOf("mTodayNear", "cTodayNear", "mTodayFar"),
        (classify(mixed, period(custom("2026-06-10", "2026-06-15"))) as TripFeed.Matches)
            .trips
            .map { it.id },
    )
  }
}
