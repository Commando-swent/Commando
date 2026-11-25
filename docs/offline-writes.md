# Safe writes across connection loss

<!-- AI assistance: Claude Code drafted this page (#394). -->

Command'o must not queue writes offline. Chat messages and trip publication follow these rules;
other writes do not yet (see Limits).

## Firestore SDK facts

- Offline persistence is on by default. An offline `set()`, `update()` or batch is queued and sent
  on reconnection; its task waits for the server with no timeout.
- A transaction is never queued. Offline it fails with `UNAVAILABLE` (about 10 s in the emulator
  test). `disableNetwork()` does not block transactions, so tests cannot use it for this.
- Cancelling the awaiting coroutine does not cancel the write: "the UI stopped waiting" never means
  "the write failed".

## Rules

1. **Create in a transaction**: write only if the document is absent; never overwrite one.
2. **One ID per intent**, created once and reused by every retry. While the outcome is uncertain,
   keep the exact data with the ID, lock editing and resend that data. Only a confirmed or
   definitively refused write releases the ID.
3. **Check before confirming**: an existing document is a success only if it belongs to the
   session's user and matches the intent; otherwise return a definitive error.
4. **Uncertain is not failed**: network, unknown, timeout and cancellation may follow a commit.
   Never say nothing was created. Validation and permission errors are definitive.
5. **Rules are the last guard**: they must forbid changing an existing document's fields.

## Where it is used

| Write | Intent kept by | Write once in |
|---|---|---|
| Chat message | `OrderChatViewModel` (saved state) | `OrderChatRepositoryFirestore.writeOnce` |
| Trip publication | `CreateTripViewModel` (saved state, same account only) | `FirebaseTripFirestoreDataSource.createTripIfAbsent` |

## Limits

- Leaving the trip form drops its pending intent; process death keeps it (#429).
- Order creation, checklist, profile photo and position writes can still be queued offline.
