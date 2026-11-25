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

No device-validation result is recorded for this revision. The following checks
remain pending and must stay unchecked in the PR description:

| Required check | Tested revision / signing source | Device | Outcome |
| --- | --- | --- | --- |
| Interactive debug launch | Not tested | None | Pending |
| Interactive release launch | Not tested | None | Pending |
| Google Sign-In on the signed CI APK | No signed CI artifact installed for this check | None | Pending |
| Authorized Firestore read on the same signed CI APK | No signed CI artifact installed for this check | None | Pending |

Local builds, APK-manifest inspection and Firebase demo-project emulator suites
are separate automated checks. They do not establish these interactive results.
Before closing #349, record the actual commit, CI artifact/run, signing source,
device/API and observed outcome for each required check.

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
