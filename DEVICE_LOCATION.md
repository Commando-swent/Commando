# Optional map location — issue #195

AI assistance: OpenAI Codex (implementation and tests).

This foundation is intentionally independent of the trip feed and shared delivery-location
filter. It adds no automatic permission request, network upload, location persistence,
background tracking, external SDK or second trip repository. The map integration connects this optional foundation to the campus map.

## Contract for the map host (part 2)

- Create `AndroidDeviceLocationProvider` with an application context and inject it into a
  screen-scoped `MapLocationViewModel`.
- Only the user's Locate action may launch the Android permission dialog. Request
  `ACCESS_COARSE_LOCATION` alone and pass the result to `request(permissionGranted)`.
  When permission is already granted, the action can call `request(true)` directly.
- Render Idle, Loading and each Resolved outcome, with localized feedback. A denial,
  disabled services or an unavailable provider must leave trip discovery usable.
- Call `stop()` when the screen really leaves the foreground or the authenticated session
  ends, but not during a configuration change (`activity.isChangingConfigurations`): the
  ViewModel survives rotation and must keep its state. `stop()` cancels pending acquisition
  and immediately clears the coordinates. Do not automatically restart on resume.
- A fix is a snapshot, not continuous tracking. The ViewModel returns to Idle when a fix
  reaches `DeviceLocation.MAX_AGE_MILLIS`; do not retain it across sessions. Android's
  delay scheduling can resume late after deep sleep. On resume, verify any retained fix
  against the monotonic clock (`isUsable(now)`) and clear stale state without acquiring a
  new fix automatically. Keep this validation in the ViewModel, invoked by the host.
- Keep device position separate from the selected handoff filter. Reuse the existing
  `distanceMeters` model helper for straight-line estimates in part 2.

The provider uses AndroidX `LocationManagerCompat.getCurrentLocation` with the network
provider, which supports approximate permission on the supported Android versions.
Devices without an enabled network provider return Unavailable even if GPS is enabled;
this implementation never escalates to precise access. Verify provider availability early
in part 2 on the emulator and on a physical phone with Google Location Accuracy enabled
and disabled. An injected-fix UI test does not establish real provider availability.
Acquisition is limited to 15 seconds by the ViewModel. Timeout, missing fixes and unsupported providers intentionally share
Unavailable: display neutral retry feedback, not a claim that the device lacks location
hardware. A distinct Timeout outcome can be introduced if the agreed UI needs that distinction.
Cancellation propagates to Android's CancellationSignal. Permission
revocation during registration is mapped to PermissionDenied. Fixes without accuracy,
invalid coordinates, nonfinite/negative accuracy, future timestamps or fixes older than
120 seconds are not displayed as confirmed positions.

References: [Android runtime permission guidance](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime)
and [AndroidX single-fix contract](https://developer.android.com/reference/androidx/core/location/LocationManagerCompat#getCurrentLocation(android.location.LocationManager,java.lang.String,android.os.CancellationSignal,java.util.concurrent.Executor,androidx.core.util.Consumer)).

## Map integration (part 2)

`MapLocationHost` launches permission access only from Locate and scopes its ViewModel
to the map destination. It stops acquisition on real background, navigation exit or session
end, while configuration changes preserve the ViewModel state. The ViewModel owns expiry.
On resume, the host asks the ViewModel to recheck monotonic fix age, including the exact
expiry boundary, without restarting acquisition.

The map shows approximate-position accuracy, straight-line distances and the count of
eligible filtered trips within the shared 500 m radius. Device location does not change the
delivery filter. The background, pins and position share a camera transform; offscreen
trips remain selectable. Out-of-campus fixes are explained without drawing a false position.

Nearby counts and badges require accuracy no larger than `HANDOFF_RADIUS_METERS` (500 m). Less precise fixes retain the approximate position and straight-line distance, but display an explanation and the total trip count instead of nearby claims. No precise-location permission is requested.

The camera center is bounded by the visible extent at its zoom, so campus-edge fixes cannot expose blank space. Camera framing survives loading, expiry and activity recreation. Only a newly acquired fix recenters it; restoring the same fix preserves user pan and zoom. Saved viewport state is separate from the transient ViewModel location fix.
