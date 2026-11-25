# Connectivity state

<!-- AI assistance: Claude Code drafted this page (#393). -->

`ConnectivityMonitor.isOnline` says whether the device has a validated internet connection. It is a
UI hint, not a promise about the server.

## Contract

- `CommandoApp` creates one `AndroidConnectivityMonitor` per activity, holding no account data. It
  registers one system callback while the app is visible and releases it 5 s after it stops.
- Signed-in screens get the shared banner from `AppScaffold`; do not add another one.
- A ViewModel that needs the state takes `ConnectivityMonitor` as a constructor parameter, passed from
  `AuthenticatedApp` through its route function. Tests use `FakeConnectivityMonitor`.

## Rules for features

- Offline, warn or disable server actions with an explanation; never rely on this signal alone.
- `true` is never success, and a switch to `false` never proves a pending write failed:
  repositories keep their own errors and timeouts.

## Limits

- Android's check shows a captive portal or blocked check as offline, and Firebase being down as
  online. Sign-in screens show no banner.
