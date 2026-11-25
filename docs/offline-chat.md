# Offline order chat (#390)

The chat uses the shared, account-scoped `SessionCache` from #432. Its typed key includes the order ID. Only a server-confirmed order context and message snapshot enter this cache; the timestamp means when the app received that confirmation, using the ViewModel’s injectable clock. Cached reads retain their original confirmation time.

Firestore's own disk cache is shared across accounts and is not used as private chat history. Either cached or pending-write metadata on the order or message query emits an unconfirmed marker with no SDK message contents. The UI may then use only its own account's previously confirmed snapshot. An empty cache never becomes an empty, authorized conversation.

The shared connectivity monitor is a hint: disconnecting switches an open chat to read-only cached history and blocks text, delay and meetup mutations, including manual retries. With cached history, draft editing remains available unless an earlier send has an uncertain outcome. Reconnecting refreshes order/trip authorization and server-confirmed messages before enabling sends; it never automatically sends the draft. Transport failures with no connectivity event also offer manual refresh.

Draft text and uncertain outbound IDs/delay values use primitive `SavedStateHandle` restoration. The matching order-details entry retains that state while the user leaves and reopens its chat; each chat route owns its own controller and collection lifetime. Back navigation while offline keeps the known details instead of forcing a server refresh. Retries preserve the original ID and payload; server transactions remain the authority. Signing out or changing accounts clears the displayed state; the shared session owner closes the cache. A same-session authentication error invalidates only this conversation, leaving other feature entries usable. Permission/deletion failures invalidate the conversation's entry. Foreground collection, connectivity observation and send jobs stop with the screen.

History survives navigation and ViewModel/activity recreation in the same app session. It is memory-only and is lost on process death or session end, as specified by the shared cache contract. Draft restoration does not recreate offline history after a cold process start. There is no offline send queue or Firestore rule change.

AI assistance: OpenAI Codex contributed the chat integration, tests and this document.
