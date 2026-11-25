# Android namespace and application identity

## Current identifiers

| Identifier | Value | Purpose |
| --- | --- | --- |
| Kotlin packages and Gradle namespace | `com.commando.app` | Source classes and generated `R` resources |
| Installed application ID | `com.android.sample` | Existing app installation, data sandbox and Firebase Android client |
| Instrumentation application ID | `com.android.sample.test` | Test APK installed beside the app |
| Launcher class | `com.commando.app.MainActivity` | Explicit activity name in the manifest |

The application ID is explicitly set in Gradle. Package migration must not change
it indirectly. Keep `google-services.json`, Firebase registration and signing
configuration unchanged for this namespace-only migration.

## Migration boundaries

Issue #348 moves feature repositories, location providers, feature UI and their
tests. Its intermediate branches retain explicit imports between migrated and
unmigrated packages so each batch compiles on its own.

Issue #349 moves authentication, profile, session, app navigation, theme, launcher
and remaining test support, then changes the Gradle namespace and generated-resource
imports. This is a source/layout migration; runtime behavior and stored document
schemas are preserved.

The CI emulator runner discovers profiles from package suffixes and source paths;
its selectors and JaCoCo source discovery support the new root. Legacy package
strings in the CI runner's regression fixtures intentionally exercise both roots.
Android backup commands continue using the installed application ID.

## Validation

Run formatting, `./gradlew check`, debug/release builds and compilation of the
instrumentation APK. Run all Firebase emulator profiles and instrumented journeys.
Verify the merged manifests contain the installed application ID above and the
new fully qualified launcher class. Launch both variants on Android.

On the signed CI APK, additionally verify Google Sign-In completes and an authorized
Firestore read succeeds. Static OAuth configuration checks and demo-project emulator
tests do not replace this production-client/device check. Record the tested revision,
device, signing source and outcome in the PR; leave unperformed checks explicitly pending.

### Device-validation status for PR #415

Recorded on 25 November 2026 for code revision `701817b6`, on the `Pixel_10`
Google Play emulator (API 37, arm64-v8a, 16 KB pages). No production data was
created or changed during the launch checks.

| Required check | Tested revision / signing source | Device | Outcome |
| --- | --- | --- | --- |
| Interactive debug launch | Local debug APK; CI APK from run `45636092785`, artifact `15033023209` | Pixel_10 / API 37 | Passed: cold launch, welcome and login screens render |
| Interactive release launch | Minified release build, temporary installation copy signed with the local debug key | Pixel_10 / API 37 | Passed: cold launch, login, Back and cold relaunch |
| Google Sign-In on the signed CI APK | Original, unmodified APK from the same CI run | Pixel_10 / API 37 | Pending: Google sign-in form reached, but the completed app session uses email/password, not Google |
| Authorized Firestore read on the same signed CI APK | Same original CI APK, authenticated email/password session | Pixel_10 / API 37 | Passed: the stored profile photo loaded and rendered through `ProfilePhotoRepositoryFirestore.load`, which explicitly reads with `Source.SERVER` |

The downloaded CI APK has SHA-256
`362ec11e13b841d3a83d6dbabf33283e4b9f156943d2ae75d1a74c78af9f9e5f`;
its signing-certificate SHA-256 is
`57031e87260ff0a112ec0289d01ee0379f3a10b117fe7c94e3b6faf328c7db62`.
It was not re-signed. The temporary release test signature is not a production
release signature or a substitute for the signed-CI Google/Firestore checks.

The completed Firebase session reports the `password` provider, without `google.com`.
Its successful server-backed photo load verifies an authorized Firestore read, but
does not verify Google OAuth on this signing certificate. No account identifiers,
credentials or profile images are included in this validation record.

Local builds, APK-manifest inspection and Firebase demo-project emulator suites
are separate automated checks. Google Sign-In remains unchecked; #349 is not fully
validated until that required check is completed on the signed CI APK.

## Future application-ID migration (not implemented here)

Changing the application ID is a separate product and release decision:

1. Register the new Android application ID as a new Android client in the chosen
   Firebase project. An existing Firebase Android client's package name cannot simply
   be renamed. Download the matching `google-services.json`.
2. Register the required SHA-1/SHA-256 fingerprints for developer, CI and release/Play
   signing keys. Verify the matching Android OAuth clients and Google Sign-In web
   client configuration. Never commit signing keys or passwords.
3. Review application-ID-dependent authorities, instrumentation IDs, scripts,
   deep links and any external integrations before release.
4. Treat the new application ID as a separate installation: it does not update or
   automatically inherit the old app's private data. Plan coexistence and communicate
   the replacement. Require authentication; do not transfer another account's cached
   data or restore excluded sessions through Android backup.
5. Check Google Sign-In, authorized Firestore operations and account isolation on the
   signed artifact before rollout. Keep the existing client available during evaluation.
6. Plan rollback before rollout. A namespace-only rollback retains the existing
   application ID, signing key and compatible data schema; distributed updates still
   need an acceptable version code. After an application-ID change, the old app is a
   different installation, so reverting code alone does not migrate users back.

References:
- [Android namespace and application ID](https://developer.android.com/build/configure-app-module)
- [Firebase Android registration](https://firebase.google.com/docs/android/setup)
