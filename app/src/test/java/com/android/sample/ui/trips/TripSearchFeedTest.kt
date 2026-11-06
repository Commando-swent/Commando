package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.lifecycle.SavedStateHandle
import com.android.sample.MainDispatcherRule
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripResult
import com.android.sample.model.CriteriaProblem
import com.android.sample.model.DeparturePeriod
import com.android.sample.model.Location
import com.android.sample.model.StoreFilter
import com.android.sample.model.Trip
import com.android.sample.model.TripLocations
import com.android.sample.model.TripSearchCriteria
import com.android.sample.model.TripSort
import com.android.sample.ui.map.TripMapUiState
import com.android.sample.ui.map.TripMapViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** One shared filter drives both the trip list and the map; they must always agree. */
class TripSearchFeedTest {
  @get:Rule val main = MainDispatcherRule()

  /** A clock tests can move forward; the feeds read it on every show. */
  private class MutableClock(var now: Instant, private val zone: ZoneId) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now
  }

  private val zurich = ZoneId.of("Europe/Zurich")
  private val clock = MutableClock(local("2026-06-15T12:00"), zurich)
  private val places = TripLocations.handoffPoints.associateBy { it.name }
  private val bc = places.getValue("BC building")
  private val migros = Location("Migros EPFL", 46.5229222, 6.5656983)
  private val coop = Location("Coop Ecublens", 46.5200, 6.5600)

  private fun local(text: String) = LocalDateTime.parse(text).atZone(zurich).toInstant()

  private fun trip(id: String, at: String, store: Location, handoff: String) =
      discoveryTrip(id, store, places.getValue(handoff), local(at))

  // Repository order (earliest departure first). From BC, CE is 689 m away (filtered out).
  private val mTodayNear = trip("mTodayNear", "2026-06-15T14:00", migros, "BC building")
  private val cTodayNear = trip("cTodayNear", "2026-06-15T15:00", coop, "BC building")
  private val mTodayFar = trip("mTodayFar", "2026-06-15T16:00", migros, "CE building")
  private val mTomorrowNear = trip("mTomorrowNear", "2026-06-16T09:00", migros, "INM building")
  private val cTomorrowFar = trip("cTomorrowFar", "2026-06-16T10:00", coop, "CE building")
  private val mixed = listOf(mTodayNear, cTodayNear, mTodayFar, mTomorrowNear, cTomorrowFar)

  private var source: TripResult<List<Trip>> = TripResult.Success(mixed)
  private val repository =
      ScriptedTripRepository(FakeTripRepository("requester"), upcoming = { source })
  private val filter = TripLocationFilterViewModel(SavedStateHandle())
  private lateinit var list: AvailableTripsViewModel
  private lateinit var map: TripMapViewModel

  private fun TestScope.start() {
    list = AvailableTripsViewModel(repository, filter.selected, true, filter.criteria, clock)
    map =
        TripMapViewModel(
            repository,
            filter.selected,
            { "requester" },
            loadImmediately = true,
            criteria = filter.criteria,
            clock = clock,
        )
    advanceUntilIdle()
  }

  private fun TestScope.reload() {
    list.retry()
    map.refresh()
    advanceUntilIdle()
  }

  private fun listIds(): Any =
      (list.uiState.value as? AvailableTripsUiState.Content)?.trips?.map { it.id }
          ?: list.uiState.value

  private fun mapIds(): Any =
      (map.uiState.value as? TripMapUiState.Content)?.trips?.map { it.id } ?: map.uiState.value

  private fun assertBoth(expected: List<String>, message: String = "") {
    assertEquals("list $message", expected, listIds())
    assertEquals("map $message", expected, mapIds())
  }

  private fun custom(start: String, end: String) =
      DeparturePeriod.Custom(LocalDate.parse(start), LocalDate.parse(end))

  @Test
  fun storePeriodAndLocationCombinationsGiveIdenticalListAndMapResults() = runTest {
    start()
    assertBoth(mixed.map { it.id })
    val cases =
        listOf(
            Triple(
                null,
                TripSearchCriteria(store = StoreFilter.MIGROS),
                listOf(
                    "mTodayNear",
                    "mTodayFar",
                    "mTomorrowNear",
                ),
            ),
            Triple(
                null,
                TripSearchCriteria(StoreFilter.MIGROS, DeparturePeriod.Today),
                listOf("mTodayNear", "mTodayFar"),
            ),
            Triple(
                null,
                TripSearchCriteria(StoreFilter.COOP, DeparturePeriod.Tomorrow),
                listOf("cTomorrowFar"),
            ),
            Triple(
                bc,
                TripSearchCriteria(store = StoreFilter.MIGROS),
                listOf("mTodayNear", "mTomorrowNear"),
            ),
            Triple(
                bc,
                TripSearchCriteria(period = DeparturePeriod.Today),
                listOf("mTodayNear", "cTodayNear"),
            ),
            Triple(
                bc,
                TripSearchCriteria(period = custom("2026-06-16", "2026-06-20")),
                listOf("mTomorrowNear"),
            ),
            Triple(null, TripSearchCriteria(), mixed.map { it.id }),
        )
    for ((point, criteria, expected) in cases) {
      if (point == null) filter.clear() else filter.select(point)
      filter.setCriteria(criteria)
      advanceUntilIdle()
      assertBoth(expected, "$point $criteria")
    }
  }

  @Test
  fun changingCriteriaOrPointRefiltersWithoutAnotherLoad() = runTest {
    start()
    assertEquals(2, repository.upcomingLoads)
    filter.setCriteria(filter.criteria.value.copy(store = StoreFilter.COOP))
    advanceUntilIdle()
    assertBoth(listOf("cTodayNear", "cTomorrowFar"))
    filter.setCriteria(filter.criteria.value.copy(period = DeparturePeriod.Today))
    advanceUntilIdle()
    assertBoth(listOf("cTodayNear"))
    filter.select(bc)
    filter.setCriteria(filter.criteria.value.copy(sort = TripSort.DISTANCE))
    filter.setCriteria(filter.criteria.value.copy(period = DeparturePeriod.AnyTime))
    advanceUntilIdle()
    assertBoth(listOf("cTodayNear"))
    assertEquals(2, repository.upcomingLoads)
  }

  @Test
  fun reloadKeepsCriteriaAndAppliesThemToFreshData() = runTest {
    filter.select(bc)
    filter.setCriteria(filter.criteria.value.copy(store = StoreFilter.MIGROS))
    start()
    assertBoth(listOf("mTodayNear", "mTomorrowNear"))
    val mTodayNew = trip("mTodayNew", "2026-06-15T17:00", migros, "SV building")
    source = TripResult.Success(listOf(cTodayNear, mTodayFar, mTodayNew, mTomorrowNear))
    reload()
    assertEquals(4, repository.upcomingLoads)
    assertEquals(TripSearchCriteria(store = StoreFilter.MIGROS), filter.criteria.value)
    assertEquals(bc, filter.selected.value)
    assertBoth(listOf("mTodayNew", "mTomorrowNear"))
  }

  @Test
  fun clearCriteriaRestoresTheLocationFilteredListAndKeepsThePoint() = runTest {
    filter.select(bc)
    filter.setCriteria(TripSearchCriteria(StoreFilter.COOP, DeparturePeriod.Tomorrow))
    start()
    assertEquals(AvailableTripsUiState.NoMatches(null), list.uiState.value)
    filter.clearCriteria()
    advanceUntilIdle()
    assertEquals(bc, filter.selected.value)
    assertBoth(listOf("mTodayNear", "cTodayNear", "mTomorrowNear"))
    assertEquals(2, repository.upcomingLoads)
  }

  @Test
  fun noMatchesNamesThePointOnlyWhenLocationAloneFiltersEverything() = runTest {
    val lausanne = Location("Lausanne gare", 46.5167, 6.6291)
    start()
    filter.select(lausanne)
    advanceUntilIdle()
    assertEquals(AvailableTripsUiState.NoMatches(lausanne), list.uiState.value)
    assertEquals(TripMapUiState.NoMatches(lausanne), map.uiState.value)
    // Sort is not a filter, so the point is still the only reason.
    filter.setCriteria(filter.criteria.value.copy(sort = TripSort.DISTANCE))
    advanceUntilIdle()
    assertEquals(AvailableTripsUiState.NoMatches(lausanne), list.uiState.value)
    assertEquals(TripMapUiState.NoMatches(lausanne), map.uiState.value)
    filter.setCriteria(filter.criteria.value.copy(store = StoreFilter.MIGROS))
    advanceUntilIdle()
    assertEquals(AvailableTripsUiState.NoMatches(null), list.uiState.value)
    assertEquals(TripMapUiState.NoMatches(null), map.uiState.value)
    // Criteria alone, without any point, also give the generic message.
    filter.clear()
    filter.setCriteria(TripSearchCriteria(StoreFilter.COOP, custom("2026-06-20", "2026-06-21")))
    advanceUntilIdle()
    assertEquals(AvailableTripsUiState.NoMatches(null), list.uiState.value)
    assertEquals(TripMapUiState.NoMatches(null), map.uiState.value)
  }

  @Test
  fun emptyFeedStaysEmptyWhateverTheCriteria() = runTest {
    source = TripResult.Success(emptyList())
    filter.select(bc)
    filter.setCriteria(TripSearchCriteria(StoreFilter.MIGROS, custom("2026-06-20", "2026-06-19")))
    start()
    assertEquals(AvailableTripsUiState.Empty, list.uiState.value)
    assertEquals(TripMapUiState.Empty, map.uiState.value)
  }

  @Test
  fun errorThenRetryRecoversWithTheSameCriteria() = runTest {
    source = TripResult.Error(TripError.NetworkError)
    filter.setCriteria(filter.criteria.value.copy(store = StoreFilter.COOP))
    start()
    assertEquals(AvailableTripsUiState.Error(TripError.NetworkError), list.uiState.value)
    assertEquals(TripMapUiState.Error(TripError.NetworkError), map.uiState.value)
    assertEquals(TripSearchCriteria(store = StoreFilter.COOP), filter.criteria.value)
    source = TripResult.Success(mixed)
    reload()
    assertBoth(listOf("cTodayNear", "cTomorrowFar"))
    assertEquals(4, repository.upcomingLoads)
  }

  @Test
  fun invertedCustomRangeIsInvalidCriteriaOnBoth() = runTest {
    start()
    filter.setCriteria(filter.criteria.value.copy(period = custom("2026-06-20", "2026-06-18")))
    advanceUntilIdle()
    assertEquals(
        AvailableTripsUiState.InvalidCriteria(CriteriaProblem.INVALID_RANGE),
        list.uiState.value,
    )
    assertEquals(TripMapUiState.InvalidCriteria(CriteriaProblem.INVALID_RANGE), map.uiState.value)
    filter.setCriteria(filter.criteria.value.copy(period = custom("2026-06-15", "2026-06-15")))
    advanceUntilIdle()
    assertBoth(listOf("mTodayNear", "cTodayNear", "mTodayFar"))
  }

  @Test
  fun customRangeExpiresWhenTheClockPassesItsLastDay() = runTest {
    filter.setCriteria(filter.criteria.value.copy(period = custom("2026-06-15", "2026-06-15")))
    start()
    assertBoth(listOf("mTodayNear", "cTodayNear", "mTodayFar"))
    // Exactly Zurich midnight after the last day: the range has ended.
    clock.now = local("2026-06-16T00:00")
    reload()
    assertEquals(
        AvailableTripsUiState.InvalidCriteria(CriteriaProblem.EXPIRED_RANGE),
        list.uiState.value,
    )
    assertEquals(TripMapUiState.InvalidCriteria(CriteriaProblem.EXPIRED_RANGE), map.uiState.value)
  }

  @Test
  fun todayMovesAcrossMidnightOnReload() = runTest {
    val late = trip("late", "2026-06-15T23:45", migros, "BC building")
    val early = trip("early", "2026-06-16T00:30", migros, "BC building")
    val next = trip("next", "2026-06-17T08:00", migros, "BC building")
    clock.now = local("2026-06-15T23:30")
    source = TripResult.Success(listOf(late, early, next))
    filter.setCriteria(filter.criteria.value.copy(period = DeparturePeriod.Today))
    start()
    assertBoth(listOf("late"))
    clock.now = local("2026-06-16T00:15")
    source = TripResult.Success(listOf(early, next))
    reload()
    assertBoth(listOf("early"))
    filter.setCriteria(filter.criteria.value.copy(period = DeparturePeriod.Tomorrow))
    advanceUntilIdle()
    assertBoth(listOf("next"))
  }

  @Test
  fun distanceSortOrdersListAndMapIdentically() = runTest {
    // From BC: BC 0 m, INM 95 m, SV 202 m, CO 280 m; CE 689 m is outside the radius.
    val co = trip("co", "2026-06-15T13:00", migros, "CO building")
    val sv = trip("sv", "2026-06-15T13:30", coop, "SV building")
    val ce = trip("ce", "2026-06-15T13:45", coop, "CE building")
    val inm = trip("inm", "2026-06-15T14:00", migros, "INM building")
    val here = trip("here", "2026-06-15T18:00", coop, "BC building")
    source = TripResult.Success(listOf(co, sv, ce, inm, here))
    start()
    assertBoth(listOf("co", "sv", "ce", "inm", "here"))
    filter.setCriteria(filter.criteria.value.copy(sort = TripSort.DISTANCE))
    advanceUntilIdle()
    // Without a point, distance sort keeps the repository order.
    assertBoth(listOf("co", "sv", "ce", "inm", "here"))
    filter.select(bc)
    advanceUntilIdle()
    assertBoth(listOf("here", "inm", "sv", "co"))
    filter.setCriteria(filter.criteria.value.copy(store = StoreFilter.COOP))
    advanceUntilIdle()
    assertBoth(listOf("here", "sv"))
    assertEquals(2, repository.upcomingLoads)
  }
}
