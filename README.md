# Command'o

## Pitch

Students often make short grocery trips to nearby stores, while other students may need only a few items but still have to make a separate trip. **Command'o** connects these students by letting shoppers publish trips they are already planning and allowing nearby users to attach small grocery requests.

Shoppers can accept requests, coordinate purchases, and arrange a predefined handoff point. As an incentive, shoppers earn a reward for each completed request and can choose to receive it either as a monetary delivery fee or as reward points.

Reward points have a fixed value (e.g., 100 points = CHF 1) and can be used as credit toward future requests on the app.

Command'o primarily targets university students who want to save time, reduce unnecessary trips, and earn a small reward while doing their own shopping.

## Split-App Model

We plan to use Firebase to manage users, trips, requests, messages, ratings, and notifications, while caching active trips and orders locally for faster access and offline support.

## Multi-User Support

Users will have personal accounts and authenticate using Firebase Authentication, with support for Google Sign-In and email/password authentication.

Once logged in, users can:
- Publish grocery trips
- Send requests
- Accept or reject orders
- Communicate with each other
- Confirm handoffs
- Rate users
- Report or block problematic accounts

Each account will have its own profile, ratings, reward point balance, active requests, and trip history.

## Sensor Use

GPS will be used to show nearby stores and grocery trips based on the user's location.

In the initial version, shoppers will manually confirm their arrival at the store or meeting point using an **"I have arrived"** button. Automatic GPS-based arrival detection and temporary live location sharing will be considered as stretch goals.

The camera will allow requesters to send photos of specific products and shoppers to share photos of alternatives when an item is unavailable. Shoppers will also be able to photograph the receipt to confirm the final purchase amount and support reimbursement.

## Offline Mode

Previously loaded trips, active requests, product lists, and order details will remain accessible offline.

Actions performed without connectivity will be marked as pending and synchronized once the device reconnects.

## Firebase emulator tests

Firebase emulator tests are grouped by the environment they require:

- `app/src/test/java/com/android/sample/emulator/auth/` uses the Auth emulator.
- `app/src/test/java/com/android/sample/emulator/firestore/adapter/` uses Firestore with
  permissive test-only rules.
- `app/src/test/java/com/android/sample/emulator/firestore/security/` uses Firestore and Auth with
  the production rules declared by `firebase.json`.

Name each test file `*EmulatorTest.kt` and place it under the matching profile. CI discovers and
runs the profile automatically. Security-rules tests must be named `*SecurityRulesEmulatorTest.kt`
and placed in the `firestore/security` profile.

The Kotlin package must match its profile directory. Connect Firebase SDK clients using
`FIRESTORE_EMULATOR_HOST` and/or `FIREBASE_AUTH_EMULATOR_HOST` for the selected profile.

Run every detected profile locally with:

```bash
bash scripts/ci/run-firebase-emulator-tests.sh
```

The security profile is enabled only when `firebase.json` explicitly points to the production
`firestore.rules` file at the repository root.

## Profile support

Profile → Help & FAQ displays expandable answers and the support email address
`info.commando@gmail.com`, which users can select and copy.
FAQ answers describe the implemented functionality and need team content review.

## Figma

The application's mockups and wireframes are available on [Figma](https://www.figma.com/design/WPmz3r0efh7jF2BQmUTfRt/Command-o-%E2%80%93-App-Mockups?node-id=0-1&t=NffdyAdRoARsGMDX-1).

## App navigation journey test

`AppJourneyTest` runs on an Android device or emulator and follows the connected #130 journey:
email sign-in → available trips → mock map and back → trip details → catalogue → review and
cart retention on Back → submission → My Orders → Order Details → profile editing → mode
switch → trip publication → My Trips → trip details → requester orders → sign-out.

It uses the real navigation graph, screens and ViewModels with in-memory authentication, trip
and product fixtures, with an explicitly injected Orders fake. It checks the demo journey;
it does not validate Firebase persistence, Google OAuth or profile photo uploads. Those
integrations have separate tests. No production account or network connection is needed.

With a device or emulator connected, run only this journey with:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.android.sample.ui.navigation.AppJourneyTest
```

The existing CI `connectedCheck` step also discovers it automatically. The instrumented report
is generated under `app/build/reports/androidTests/connected/debug/`.

## Persistent Orders Android check (#145)

The authenticated app uses `OrderRepositoryFirestore` by default; tests may inject a fake.
`PersistentOrderJourneyTest` uses named Firebase clients in `demo-commando`, production rules,
and a fresh authenticated client to check save → My Orders → Details and server persistence.
It skips unless explicitly enabled; it does not test a deployed Firebase project.

With Auth/Firestore emulators running and an Android emulator connected:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.android.sample.ui.navigation.PersistentOrderJourneyTest \
  -Pandroid.testInstrumentationRunnerArguments.firebaseEmulatorHost=10.0.2.2 \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

Use `firebase emulators:exec --project demo-commando --only auth,firestore '<command above>'`
to run with the checked-in rules. HTTP is allowed only for local emulator hosts in debug builds.
The test saves `cache/persistent-order.png` in the target app for visual inspection.
See `ORDERS_FIRESTORE.md` for schema, error semantics and production deployment prerequisites.

The instrumentation configuration excludes legacy `protobuf-lite` from Espresso-contrib, which
otherwise shadows Firestore's runtime ([Android Test issue #999](https://github.com/android/android-test/issues/999)).
