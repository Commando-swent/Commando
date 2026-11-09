# Pending-order editing screenshots

Captured on a Pixel 10a emulator running Android 17 (API 37), at 1080 × 2424, using isolated in-memory fixtures. All four Android capture checks passed.

- [Edit button](edit-button.png): requester details for an editable pending order.
- [Review edit](edit-review.png): changed quantity, commission input and confirmation action.
- [Conflict](edit-conflict.png): retained draft and navigation to the persisted order.
- [Editing unavailable](edit-unavailable.png): contextual feedback inside the safe, scrollable details content.

[Editable Figma states](https://www.figma.com/design/WPmz3r0efh7jF2BQmUTfRt?node-id=906-10094)

## Regenerate

The [manual capture fixture](CaptureFixture.kt) renders the production Compose components and captures the Android window, including system bars. With an Android emulator connected:

```sh
cp docs/screenshots/order-edit/CaptureFixture.kt app/src/androidTest/java/com/android/sample/EditOrderReviewCaptureTest.kt
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.sample.EditOrderReviewCaptureTest
```

The Android Gradle test runner may uninstall the app after completion. To retain the files for export, install both generated APKs and run the same class through `adb shell am instrument`; the PNGs are written to the target app's `files` directory. Remove the temporary Android-test source after capture. The fixture is documentation tooling and is not part of the default CI test suite.

AI assistance: OpenAI Codex assisted capture tooling and this documentation.
