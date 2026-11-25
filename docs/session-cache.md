# Session cache

<!-- AI assistance: Claude Code drafted this page (#394, sub-issue #428). -->

`SessionCache` keeps data the server already confirmed, with the time it was confirmed, so a
screen can show it while the server cannot be reached. Each signed-in account gets its own cache.

## Contract

- `rememberSessionDependencies` creates one cache per account, bound to the session identity
  (`orders::currentUserId`). Pass `session.cache` from `AuthenticatedApp` through the route to the
  ViewModel, like the repositories.
- Store only values the server confirmed: results read with `Source.SERVER` (as the order
  repository does), or snapshots whose `metadata.isFromCache` is false. A Firestore result served
  from its local cache is not a confirmation and must not be stored.
- Create keys with `SessionCache.key<T>(name)`, for example `key<Order>("order/$id")`. A key's
  type is part of its identity, so two keys with the same name and different types never share an
  entry.
- As soon as the account signs out or changes, the cache is emptied for good: `get` returns `null`,
  `put` returns `false` and `remove` does nothing. The next account starts with a new, empty
  cache. `put` returning `false` means the session ended: drop the result, do not show it.
- Every operation runs under one lock, so a write can never land after the cache was closed.
- Tests use a real `SessionCache { uid }` with a variable `uid`.

## Example

`Content` and `Unavailable` stand for the screen's own UI states.

```kotlin
private val orderKey = SessionCache.key<Order>("order/$orderId")

fun load() = viewModelScope.launch {
  when (val result = repository.getOrderById(orderId)) { // Reads with Source.SERVER.
    is OrderResult.Success -> {
      // False once the session has ended: this response belongs to the previous account.
      if (!cache.put(orderKey, result.data)) return@launch
      _state.value = Content(result.data, stale = false)
    }
    is OrderResult.Error -> {
      // Only Unknown may fall back. Definitive errors (NotFound, PermissionDenied, ...) are shown
      // and invalidate the cached entry.
      if (result.error != OrderError.Unknown) cache.remove(orderKey)
      val cached = cache.get(orderKey).takeIf { result.error == OrderError.Unknown }
      _state.value = cached?.let { Content(it.value, stale = true, confirmedAt = it.confirmedAt) }
          ?: Unavailable(result.error)
    }
  }
}
```

`OrderError.Unknown` covers both an unreachable server (`NETWORK_UNAVAILABLE`) and unexpected
failures: the order repository does not tell them apart. Both are shown as stale data with its
confirmation time, never as current. A feature that must distinguish them needs a dedicated network
error in its repository first.

## Limits

- Memory only: the cache is lost when Android stops the app, and nothing is written to disk.
- It does not replace Firestore's own offline cache, which is shared by all accounts on the
  device. Do not read private data from it without checking its owner against the session.
- Showing cached data never proves it is current; always mark it as stale with `confirmedAt`.
