# Connectivity state

<!-- AI assistance: Claude Code drafted this page (#393). -->

`ConnectivityMonitor.isOnline` says whether the device has a validated internet connection. It is
a hint for the UI, not a promise about the server.

## Contract

- `CommandoApp` creates one `AndroidConnectivityMonitor` per activity. It registers a single
  system callback while someone collects, and survives rotation and account changes. It holds no
  account data.
- Signed-in screens get the shared banner from `AppScaffold`; do not add another one.
- A ViewModel that needs the state takes `ConnectivityMonitor` (or its `isOnline` flow) as a
  constructor parameter. Pass it from `AuthenticatedApp` to your route function (`tripRoutes`,
  `ordersRoutes`, ...) as a `connectivity` parameter. Tests use `FakeConnectivityMonitor`.

## Rules for features

- Offline, warn or disable server actions with an explanation; never rely on this signal alone.
- Never treat `isOnline == true` as success: the server can still fail, so repositories keep their
  own errors and timeouts.
- Never treat a switch to offline as proof that a pending write failed.

## Limits

- "Validated" comes from Android's own check: a captive portal or a blocked check shows offline even
  if Firebase might work, and a network with Firebase down still shows online.
- The banner covers signed-in screens only; sign-in screens rely on their own errors.
