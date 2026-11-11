package com.android.sample.data.repository

// AI assistance: Claude Code.
import android.content.Context
import com.android.sample.model.DeparturePeriod
import com.android.sample.model.SavedTripSearch
import com.android.sample.model.StoreFilter
import com.android.sample.model.TripSearchCriteria
import com.android.sample.model.TripSort
import com.android.sample.model.normalizedSearchName
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Keeps presets on this device in private SharedPreferences, one JSON list per user ID, so each
 * account only ever reads and writes its own presets. They are not synced to other devices.
 * Malformed entries are skipped; an unreadable list is InvalidData and is never overwritten.
 */
class SavedTripSearchRepositoryLocal(
    context: Context,
    private val currentUserId: () -> String?,
    private val now: () -> Instant = Instant::now,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SavedTripSearchRepository {
  private val preferences =
      context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  override suspend fun getSavedSearches(): TripResult<List<SavedTripSearch>> = edit { it }

  override suspend fun saveSearch(
      name: String,
      criteria: TripSearchCriteria,
  ): TripResult<SavedTripSearch> {
    if (currentUserId() == null) return TripResult.Error(TripError.PermissionDenied)
    val valid = normalizedSearchName(name) ?: return TripResult.Error(TripError.InvalidData)
    // Stored as epoch milliseconds, so the returned preset must equal the one read back.
    val createdAt = now().truncatedTo(ChronoUnit.MILLIS)
    val saved = SavedTripSearch(UUID.randomUUID().toString(), valid, criteria, createdAt)
    return edit { it + saved }.map { saved }
  }

  override suspend fun renameSearch(id: String, name: String): TripResult<Unit> {
    if (currentUserId() == null) return TripResult.Error(TripError.PermissionDenied)
    val valid = normalizedSearchName(name) ?: return TripResult.Error(TripError.InvalidData)
    return edit { searches ->
      if (searches.none { it.id == id }) return@edit null
      searches.map { if (it.id == id) it.copy(name = valid) else it }
    }
        .map {}
  }

  override suspend fun deleteSearch(id: String): TripResult<Unit> = edit { searches ->
    searches.filterNot { it.id == id }
  }
      .map {}

  /**
   * Reads the current user's list, applies [change] and stores the result when it differs. A null
   * result from [change] means the targeted preset does not exist.
   */
  private suspend fun edit(
      change: (List<SavedTripSearch>) -> List<SavedTripSearch>?
  ): TripResult<List<SavedTripSearch>> {
    val uid = currentUserId() ?: return TripResult.Error(TripError.PermissionDenied)
    return withContext(ioDispatcher) {
      // ponytail: one lock for all users; per-user locks if presets ever see real contention.
      synchronized(LOCK) {
        val current =
            readSearches(preferences.getString(uid, null))
                ?: return@withContext TripResult.Error(TripError.InvalidData)
        val updated = change(current) ?: return@withContext TripResult.Error(TripError.NotFound)
        // commit(), not apply(): success is only reported once the change is on disk.
        if (
            updated != current &&
                !preferences.edit().putString(uid, writeSearches(updated)).commit()
        ) {
          return@withContext TripResult.Error(TripError.Unknown)
        }
        TripResult.Success(updated.sortedWith(compareBy({ it.createdAt }, { it.id })))
      }
    }
  }

  private companion object {
    const val PREFERENCES = "saved_trip_searches"
    val LOCK = Any()
  }
}

private fun <T, R> TripResult<T>.map(transform: (T) -> R): TripResult<R> =
    when (this) {
      is TripResult.Success -> TripResult.Success(transform(data))
      is TripResult.Error -> this
    }

private const val ID = "id"
private const val NAME = "name"
private const val STORE = "store"
private const val PERIOD = "period"
private const val START = "start"
private const val END = "end"
private const val SORT = "sort"
private const val CREATED_AT = "createdAt"
private const val ANY_TIME = "any"
private const val TODAY = "today"
private const val TOMORROW = "tomorrow"
private const val CUSTOM = "custom"

/** Stores enum names, ISO dates (custom periods only) and epoch milliseconds. */
internal fun writeSearches(searches: List<SavedTripSearch>): String =
    JSONArray(
            searches.map { search ->
              val period = search.criteria.period
              JSONObject()
                  .put(ID, search.id)
                  .put(NAME, search.name)
                  .put(STORE, search.criteria.store.name)
                  .put(
                      PERIOD,
                      when (period) {
                        DeparturePeriod.AnyTime -> ANY_TIME
                        DeparturePeriod.Today -> TODAY
                        DeparturePeriod.Tomorrow -> TOMORROW
                        is DeparturePeriod.Custom -> CUSTOM
                      },
                  )
                  .put(START, (period as? DeparturePeriod.Custom)?.start?.toString())
                  .put(END, (period as? DeparturePeriod.Custom)?.end?.toString())
                  .put(SORT, search.criteria.sort.name)
                  .put(CREATED_AT, search.createdAt.toEpochMilli())
            }
        )
        .toString()

/** The presets in [json] (empty when null), skipping malformed entries; null if unparsable. */
internal fun readSearches(json: String?): List<SavedTripSearch>? {
  if (json == null) return emptyList()
  val array =
      try {
        JSONArray(json)
      } catch (_: JSONException) {
        return null
      }
  return (0 until array.length()).mapNotNull { index ->
    array.optJSONObject(index)?.let(::readSearch)
  }
}

// ponytail: skipped entries are dropped on the next write, so renaming or removing a StoreFilter /
// TripSort value would erase presets using it; add a migration here before doing that.
private fun readSearch(entry: JSONObject): SavedTripSearch? {
  val id = entry.optString(ID).takeIf { it.isNotEmpty() } ?: return null
  val name = normalizedSearchName(entry.optString(NAME)) ?: return null
  val store = StoreFilter.entries.find { it.name == entry.optString(STORE) } ?: return null
  val sort = TripSort.entries.find { it.name == entry.optString(SORT) } ?: return null
  val createdAt = (entry.opt(CREATED_AT) as? Number)?.let { Instant.ofEpochMilli(it.toLong()) }
  if (createdAt == null) return null
  val period =
      when (entry.optString(PERIOD)) {
        ANY_TIME -> DeparturePeriod.AnyTime
        TODAY -> DeparturePeriod.Today
        TOMORROW -> DeparturePeriod.Tomorrow
        CUSTOM ->
            DeparturePeriod.Custom(
                isoDate(entry.optString(START)) ?: return null,
                isoDate(entry.optString(END)) ?: return null,
            )
        else -> return null
      }
  return SavedTripSearch(id, name, TripSearchCriteria(store, period, sort), createdAt)
}

private fun isoDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
