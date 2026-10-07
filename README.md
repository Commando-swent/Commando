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

## Figma

The application's mockups and wireframes are available on [Figma](https://www.figma.com/design/WPmz3r0efh7jF2BQmUTfRt/Command-o-%E2%80%93-App-Mockups?node-id=0-1&t=NffdyAdRoARsGMDX-1).
