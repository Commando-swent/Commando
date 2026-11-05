# Optional map location — issue #195, part 1

AI assistance: OpenAI Codex (implementation and tests).

This foundation is intentionally independent of the trip feed and shared delivery-location
filter. It adds no automatic permission request, network upload, location persistence,
background tracking, external SDK or second trip repository. The existing map behaves as
before until the screen integration in part 2.

## Contract for the map host (part 2)

- Create `AndroidDeviceLocationProvider` with an application context and inject it into a
  screen-scoped `MapLocationViewModel`.
- Only the user's Locate action may launch the Android permission dialog. Request
  `ACCESS_COARSE_LOCATION` alone and pass the result to `request(permissionGranted)`.
  When permission is already granted, the action can call `request(true)` directly.
- Render Idle, Loading and each Resolved outcome, with localized feedback. A denial,
  disabled services or an unavailable provider must leave trip discovery usable.
- Call `stop()` when the screen leaves the foreground or the authenticated session ends;
  it cancels pending acquisition and immediately clears the coordinates. ViewModel
  disposal also cancels its coroutine. Do not automatically restart on resume.
- A fix is a snapshot, not continuous tracking. Recheck `isUsable(SystemClock.elapsedRealtime())`
  before using a retained fix for recentering or nearby-trip distances. Hide it once it
  exceeds `DeviceLocation.MAX_AGE_MILLIS`; do not retain it across sessions.
- Keep device position separate from the selected handoff filter. Reuse the existing
  `distanceMeters` model helper for straight-line estimates in part 2.

The provider uses AndroidX `LocationManagerCompat.getCurrentLocation` with the network
provider, which supports approximate permission on the supported Android versions.
Devices without an enabled network provider return Unavailable even if GPS is enabled;
this implementation never escalates to precise access. Acquisition is limited to 15 seconds
by the ViewModel. Cancellation propagates to Android's CancellationSignal. Permission
revocation during registration is mapped to PermissionDenied. Fixes without accuracy,
invalid coordinates, nonfinite/negative accuracy, future timestamps or fixes older than
120 seconds are not displayed as confirmed positions.

References: [Android runtime permission guidance](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime)
and [AndroidX single-fix contract](https://developer.android.com/reference/androidx/core/location/LocationManagerCompat#getCurrentLocation(android.location.LocationManager,java.lang.String,android.os.CancellationSignal,java.util.concurrent.Executor,androidx.core.util.Consumer)).

Part 2 still needs the actual permission dialog, lifecycle/session wiring, map position,
recentring, nearby-trip presentation, screen tests and on-device verification. Part 1 does
not close #195.

## Local validation

Validated on main baseline `3579f26` with Temurin Java 21, matching CI:

- `./gradlew ktfmtCheck`: passed.
- `./gradlew check assembleDebug jacocoTestReport`: passed; 769 tests, no failures,
  errors or skipped tests, including 20 new foundation tests.
- JaCoCo for the four new production files: 86/87 lines (98.85%) and all 56 branches
  covered. These are local figures, not a SonarCloud quality-gate result.
- No running Android emulator/device was available. Actual permission-dialog and device
  verification remain with the screen integration.
- Open PRs #220 and #222 shared none of this part's modified production paths when checked.
  This does not guarantee against future changes.

An initial lint invocation under Java 22 crashed internally. The final Java 21 run passed
with every check enabled. Existing coverage execution files produced mismatch warnings for
unrelated repository classes; none of the new location classes had mismatches.
