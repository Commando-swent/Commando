package com.android.sample.data.repository

// AI assistance: Claude Code.
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.android.sample.model.DeparturePeriod
import com.android.sample.model.SavedTripSearch
import com.android.sample.model.StoreFilter
import com.android.sample.model.TripSearchCriteria
import com.android.sample.model.TripSort
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SavedTripSearchRepositoryLocalTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val prefs: SharedPreferences =
      context.getSharedPreferences("saved_trip_searches", Context.MODE_PRIVATE)
  private val t0 = Instant.parse("2026-11-04T10:00:00Z")
  private var uid: String? = "alice"
  private var clock = t0

  private val custom =
      TripSearchCriteria(
          StoreFilter.COOP,
          DeparturePeriod.Custom(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 9)),
          TripSort.DISTANCE,
      )

  @Before
  fun clear() {
    prefs.edit().clear().commit()
  }

  @After
  fun clearAfter() {
    prefs.edit().clear().commit()
  }

  private fun TestScope.repo() =
      SavedTripSearchRepositoryLocal(
          context,
          { uid },
          { clock },
          UnconfinedTestDispatcher(testScheduler),
      )

  private fun <T> TripResult<T>.ok(): T = (this as TripResult.Success).data

  private fun entry(
      id: String = "x",
      name: String? = "Name",
      store: String? = "MIGROS",
      period: String? = "any",
      start: String? = null,
      end: String? = null,
      sort: String? = "EARLIEST_DEPARTURE",
      createdAt: Long? = t0.toEpochMilli(),
  ): String {
    val fields =
        listOf(
                "id" to id,
                "name" to name,
                "store" to store,
                "period" to period,
                "start" to start,
                "end" to end,
                "sort" to sort,
            )
            .filter { it.second != null }
            .map { "\"${it.first}\":\"${it.second}\"" } +
            listOfNotNull(createdAt?.let { "\"createdAt\":$it" })
    return fields.joinToString(",", "{", "}")
  }

  @Test
  fun saveAndGetRoundTripEveryCriteriaShape() = runTest {
    val periods =
        listOf(
            DeparturePeriod.AnyTime,
            DeparturePeriod.Today,
            DeparturePeriod.Tomorrow,
            DeparturePeriod.Custom(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 9)),
        )
    val all =
        StoreFilter.entries.flatMap { store ->
          periods.flatMap { period ->
            TripSort.entries.map { TripSearchCriteria(store, period, it) }
          }
        }
    assertEquals(24, all.size)
    val repository = repo()
    val saved = all.mapIndexed { index, criteria ->
      clock = t0.plusSeconds(index.toLong())
      repository.saveSearch("Preset $index", criteria).ok()
    }
    saved.forEachIndexed { index, search ->
      assertEquals("Preset $index", search.name)
      assertEquals(all[index], search.criteria)
      assertEquals(t0.plusSeconds(index.toLong()), search.createdAt)
    }
    assertEquals(saved, repository.getSavedSearches().ok())
    assertEquals(saved, repo().getSavedSearches().ok())
  }

  @Test
  fun listIsOrderedByCreatedAtThenId() = runTest {
    val later = SavedTripSearch("a", "Later", TripSearchCriteria(), t0.plusSeconds(5))
    val tieB = SavedTripSearch("b", "Tie B", TripSearchCriteria(), t0)
    val tieA = SavedTripSearch("a0", "Tie A", custom, t0)
    prefs.edit().putString("alice", writeSearches(listOf(later, tieB, tieA))).commit()

    assertEquals(listOf(tieA, tieB, later), repo().getSavedSearches().ok())
  }

  @Test
  fun savesAppendInCreationOrder() = runTest {
    val repository = repo()
    clock = t0.plusSeconds(10)
    val second = repository.saveSearch("Second", custom).ok()
    clock = t0
    val first = repository.saveSearch("First", TripSearchCriteria()).ok()

    assertEquals(listOf(first, second), repository.getSavedSearches().ok())
  }

  @Test
  fun createdAtWithSubMillisecondPrecisionSurvivesRoundTrip() = runTest {
    // Instant.now() has sub-millisecond precision on the JVM; the stored value is epoch millis.
    clock = Instant.parse("2026-11-04T10:00:00.123456Z")
    val repository = repo()
    val saved = repository.saveSearch("Precise", custom).ok()

    assertEquals(listOf(saved), repository.getSavedSearches().ok())
  }

  @Test
  fun nameIsTrimmedAndFortyCharsAccepted() = runTest {
    val repository = repo()
    assertEquals("Weekend Coop", repository.saveSearch("  Weekend Coop \n", custom).ok().name)
    val forty = "a".repeat(40)
    clock = t0.plusSeconds(1)
    assertEquals(forty, repository.saveSearch(forty, custom).ok().name)
    clock = t0.plusSeconds(2)
    assertEquals(forty, repository.saveSearch("  $forty  ", custom).ok().name)
    assertEquals(
        listOf("Weekend Coop", forty, forty),
        repo().getSavedSearches().ok().map { it.name },
    )
  }

  @Test
  fun invalidNamesAreRejectedAndNothingStored() = runTest {
    val repository = repo()
    for (name in listOf("", "   ", "\t\n", "a".repeat(41), " ${"b".repeat(41)} ")) {
      assertEquals(TripResult.Error(TripError.InvalidData), repository.saveSearch(name, custom))
    }
    assertNull(prefs.getString("alice", null))
    assertEquals(emptyList<SavedTripSearch>(), repository.getSavedSearches().ok())
  }

  @Test
  fun renameChangesOnlyTheName() = runTest {
    val repository = repo()
    val first = repository.saveSearch("First", custom).ok()
    clock = t0.plusSeconds(1)
    val second = repository.saveSearch("Second", TripSearchCriteria()).ok()

    assertEquals(TripResult.Success(Unit), repository.renameSearch(first.id, "  Renamed  "))

    val expected = listOf(first.copy(name = "Renamed"), second)
    assertEquals(expected, repository.getSavedSearches().ok())
    assertEquals(expected, repo().getSavedSearches().ok())
  }

  @Test
  fun renameUnknownIdIsNotFoundAndWritesNothing() = runTest {
    val repository = repo()
    assertEquals(TripResult.Error(TripError.NotFound), repository.renameSearch("missing", "Name"))
    assertNull(prefs.getString("alice", null))

    val saved = repository.saveSearch("Kept", custom).ok()
    val stored = prefs.getString("alice", null)
    assertEquals(TripResult.Error(TripError.NotFound), repository.renameSearch("missing", "Name"))
    assertEquals(stored, prefs.getString("alice", null))
    assertEquals(listOf(saved), repository.getSavedSearches().ok())
  }

  @Test
  fun renameWithInvalidNameIsInvalidData() = runTest {
    val repository = repo()
    val saved = repository.saveSearch("Kept", custom).ok()
    assertEquals(TripResult.Error(TripError.InvalidData), repository.renameSearch(saved.id, " "))
    assertEquals(
        TripResult.Error(TripError.InvalidData),
        repository.renameSearch(saved.id, "c".repeat(41)),
    )
    assertEquals(listOf(saved), repository.getSavedSearches().ok())
  }

  @Test
  fun deleteRemovesOnlyThatPreset() = runTest {
    val repository = repo()
    val first = repository.saveSearch("First", custom).ok()
    clock = t0.plusSeconds(1)
    val second = repository.saveSearch("Second", TripSearchCriteria()).ok()
    clock = t0.plusSeconds(2)
    val third = repository.saveSearch("Third", custom).ok()

    assertEquals(TripResult.Success(Unit), repository.deleteSearch(second.id))

    assertEquals(listOf(first, third), repository.getSavedSearches().ok())
    assertEquals(listOf(first, third), repo().getSavedSearches().ok())
  }

  @Test
  fun deletingMissingIdSucceedsAndChangesNothing() = runTest {
    val repository = repo()
    assertEquals(TripResult.Success(Unit), repository.deleteSearch("missing"))
    assertNull(prefs.getString("alice", null))

    val saved = repository.saveSearch("Kept", custom).ok()
    val stored = prefs.getString("alice", null)
    assertEquals(TripResult.Success(Unit), repository.deleteSearch("missing"))
    assertEquals(TripResult.Success(Unit), repository.deleteSearch(saved.id))
    assertEquals(TripResult.Success(Unit), repository.deleteSearch(saved.id))
    assertEquals(emptyList<SavedTripSearch>(), repository.getSavedSearches().ok())
    assertTrue(stored != prefs.getString("alice", null))
  }

  @Test
  fun presetsPersistAcrossRepositoryInstances() = runTest {
    val writer = repo()
    val a = writer.saveSearch("A", custom).ok()
    clock = t0.plusSeconds(1)
    val b = writer.saveSearch("B", TripSearchCriteria(StoreFilter.MIGROS)).ok()
    clock = t0.plusSeconds(2)
    val c = writer.saveSearch("C", TripSearchCriteria(period = DeparturePeriod.Today)).ok()
    assertEquals(listOf(a, b, c), repo().getSavedSearches().ok())

    val second = repo()
    assertEquals(TripResult.Success(Unit), second.renameSearch(b.id, "B2"))
    assertEquals(TripResult.Success(Unit), second.deleteSearch(a.id))

    assertEquals(listOf(b.copy(name = "B2"), c), repo().getSavedSearches().ok())
    assertEquals(listOf(b.copy(name = "B2"), c), writer.getSavedSearches().ok())
  }

  @Test
  fun eachUserOnlySeesAndChangesOwnPresets() = runTest {
    val repository = repo()
    val alices = repository.saveSearch("Alice", custom).ok()

    uid = "bob"
    assertEquals(emptyList<SavedTripSearch>(), repository.getSavedSearches().ok())
    val bobs = repository.saveSearch("Bob", TripSearchCriteria(StoreFilter.MIGROS)).ok()
    assertEquals(TripResult.Error(TripError.NotFound), repository.renameSearch(alices.id, "Hack"))
    assertEquals(TripResult.Success(Unit), repository.deleteSearch(alices.id))
    assertEquals(listOf(bobs), repository.getSavedSearches().ok())

    uid = "alice"
    assertEquals(listOf(alices), repository.getSavedSearches().ok())
    assertEquals(listOf(alices), repo().getSavedSearches().ok())
    uid = "bob"
    assertEquals(listOf(bobs), repo().getSavedSearches().ok())
  }

  @Test
  fun signedOutIsPermissionDeniedAndWritesNothing() = runTest {
    uid = "alice"
    val saved = repo().saveSearch("Alice", custom).ok()
    val stored = prefs.all.toMap()

    uid = null
    val repository = repo()
    val denied = TripResult.Error(TripError.PermissionDenied)
    assertEquals(denied, repository.getSavedSearches())
    assertEquals(denied, repository.saveSearch("Name", custom))
    assertEquals(denied, repository.renameSearch(saved.id, "Name"))
    assertEquals(denied, repository.deleteSearch(saved.id))
    assertEquals(stored, prefs.all.toMap())

    uid = "alice"
    assertEquals(listOf(saved), repository.getSavedSearches().ok())
  }

  @Test
  fun signedOutWithInvalidNameIsStillPermissionDenied() = runTest {
    // Contract: without a current user every call is PermissionDenied, whatever the arguments.
    uid = null
    val repository = repo()
    val denied = TripResult.Error(TripError.PermissionDenied)
    assertEquals(denied, repository.saveSearch(" ", custom))
    assertEquals(denied, repository.renameSearch("x", "a".repeat(41)))
    assertTrue(prefs.all.isEmpty())
  }

  @Test
  fun unparsableListIsInvalidDataAndNeverOverwritten() = runTest {
    for (raw in listOf("not json", "{\"id\":\"x\"}", "[{\"id\":")) {
      prefs.edit().putString("alice", raw).commit()
      val repository = repo()
      val invalid = TripResult.Error(TripError.InvalidData)
      assertEquals(invalid, repository.getSavedSearches())
      assertEquals(invalid, repository.saveSearch("Name", custom))
      assertEquals(invalid, repository.renameSearch("x", "Name"))
      assertEquals(invalid, repository.deleteSearch("x"))
      assertEquals(raw, prefs.getString("alice", null))
    }
  }

  @Test
  fun corruptListOfOneUserDoesNotAffectAnother() = runTest {
    prefs.edit().putString("alice", "not json").commit()
    uid = "bob"
    val saved = repo().saveSearch("Bob", custom).ok()
    assertEquals(listOf(saved), repo().getSavedSearches().ok())
    assertEquals("not json", prefs.getString("alice", null))
  }

  @Test
  fun malformedEntriesAreSkipped() = runTest {
    val valid =
        entry(
            id = "ok",
            name = "Good",
            store = "COOP",
            period = "custom",
            start = "2026-11-05",
            end = "2026-11-09",
            sort = "DISTANCE",
        )
    val malformed =
        listOf(
            entry(id = ""),
            entry(name = null),
            entry(name = "  "),
            entry(name = "d".repeat(41)),
            entry(store = null),
            entry(store = "LIDL"),
            entry(period = null),
            entry(period = "yesterday"),
            entry(period = "custom"),
            entry(period = "custom", start = "2026-11-05"),
            entry(period = "custom", start = "2026-13-40", end = "2026-11-09"),
            entry(period = "custom", start = "2026-11-05", end = "tomorrow"),
            entry(sort = null),
            entry(sort = "CHEAPEST"),
            entry(createdAt = null),
            "42",
            "\"text\"",
            "null",
        )
    prefs.edit().putString("alice", (malformed + valid).joinToString(",", "[", "]")).commit()

    assertEquals(
        listOf(SavedTripSearch("ok", "Good", custom, t0)),
        repo().getSavedSearches().ok(),
    )
  }

  @Test
  fun writeAndReadSearchesRoundTrip() {
    val searches =
        listOf(
            SavedTripSearch("1", "Custom", custom, t0),
            SavedTripSearch("2", "Default", TripSearchCriteria(), t0.plusSeconds(60)),
            SavedTripSearch(
                "3",
                "Tomorrow",
                TripSearchCriteria(StoreFilter.MIGROS, DeparturePeriod.Tomorrow, TripSort.DISTANCE),
                t0.plusMillis(1),
            ),
        )
    assertEquals(searches, readSearches(writeSearches(searches)))
    assertEquals(emptyList<SavedTripSearch>(), readSearches(writeSearches(emptyList())))
    assertEquals(emptyList<SavedTripSearch>(), readSearches(null))
    assertEquals(emptyList<SavedTripSearch>(), readSearches("[]"))
    assertNull(readSearches("garbage"))
  }

  @Test
  fun storedJsonDoesNotContainDeliveryPointOrExtraFields() {
    val json = writeSearches(listOf(SavedTripSearch("1", "Custom", custom, t0)))
    assertEquals(
        "[{\"id\":\"1\",\"name\":\"Custom\",\"store\":\"COOP\",\"period\":\"custom\"," +
            "\"start\":\"2026-11-05\",\"end\":\"2026-11-09\",\"sort\":\"DISTANCE\"," +
            "\"createdAt\":${t0.toEpochMilli()}}]",
        json,
    )
    val anyTime = writeSearches(listOf(SavedTripSearch("2", "Any", TripSearchCriteria(), t0)))
    assertTrue(!anyTime.contains("start") && !anyTime.contains("end"))
  }
}
