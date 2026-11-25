# Session cache

<!-- AI assistance: Claude Code drafted this page (#394, sub-issue #428). -->

`SessionCache` keeps data the server already confirmed, with the time it was confirmed, so a
screen can show it while the server cannot be reached. Each signed-in account gets its own cache.

## Contract

- `rememberSessionDependencies` creates one cache per account, bound to the session identity
  (`orders::currentUserId`). Pass `session.cache` from `AuthenticatedApp` through the route to the
  ViewModel, like the repositories.
- Store only server-confirmed values, under a typed `SessionCache.Key<T>`. Keys are compared by
  name only, so define each key once, next to its repository or ViewModel, with a name prefixed by
  its type (`"order/$id"`, `"myOrders"`), and never reuse a name for another type.
- As soon as the account signs out or changes, even briefly, the cache is emptied for good: `get`
  returns `null` and `put` returns `false`. A response that arrives late is therefore dropped, and
  the next account starts with a new, empty cache.
- Tests use a real `SessionCache { uid }` with a variable `uid`.

## Example

`Content` and `Unavailable` stand for the screen's own UI states.

```kotlin
private val orderKey = SessionCache.Key<Order>("order/$orderId")

fun load() = viewModelScope.launch {
  when (val result = repository.getOrderById(orderId)) {
    is OrderResult.Success -> {
      cache.put(orderKey, result.data) // confirmedAt defaults to now
      _state.value = Content(result.data, stale = false)
    }
    // Only a network failure (Unknown) may fall back to the cache. NotFound, PermissionDenied and
    // the other definitive errors must be shown, never hidden behind stale data.
    is OrderResult.Error -> {
      val cached = cache.get(orderKey).takeIf { result.error == OrderError.Unknown }
      _state.value = cached?.let { Content(it.value, stale = true, confirmedAt = it.confirmedAt) }
          ?: Unavailable(result.error)
    }
  }
}
```

## Limits

- Memory only: the cache is lost when Android stops the app, and nothing is written to disk.
- It does not replace Firestore's own offline cache, which is shared by all accounts on the
  device. Do not read private data from it without checking its owner against the session.
- Showing cached data never proves it is current; always mark it as stale with `confirmedAt`.
