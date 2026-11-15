# Android backup policy

<!-- AI assistance: OpenAI Codex. -->

Command'o excludes app-managed files from legacy Android backup, Android 12+ cloud backup,
and Android 12+ device-to-device transfer. A restored installation requires sign-in. Data
stored on Firebase can still be loaded by the account that is allowed to access it.

| Data on the device | Android storage | Legacy (API 28–30) | Cloud (API 31+) | Device transfer (API 31+) |
| --- | --- | --- | --- | --- |
| Saved trip searches | `sharedpref/saved_trip_searches.xml` | Excluded | Excluded | Excluded |
| Firebase Auth session and sign-in state | SDK-managed private preferences | Excluded | Excluded | Excluded |
| Firestore local cache and pending writes | SDK-managed databases and their sidecars | Excluded | Excluded | Excluded |
| App-managed locale record (AppCompat on older Android versions) and other private files | `file`, `root`, and `sharedpref` | Excluded | Excluded | Excluded |
| Other app-managed database, external, or device-protected files | `database`, `external`, `device_root`, `device_file`, `device_database`, and `device_sharedpref` | Excluded | Excluded | Excluded |
| Temporary files and files marked no-backup | Android cache, code cache, and no-backup directories | Excluded by Android | Excluded by Android | Excluded by Android |

The rules exclude whole storage domains so that a Firebase SDK update or a new
account-scoped file cannot silently move private data into the backup. There is no
DataStore usage. The rules apply to copying data between installations. Firestore
still caches data on the original installation during normal use. Android 13+ manages
the per-app language setting separately from the app-managed storage in the table.

## Check backup and restore

Use a disposable device or emulator and an APK whose merged manifest enables backup.
The standard debug manifest disables backup. Use matching app IDs and signing keys for
the backup and reinstall. Run the [Android backup test procedure](https://developer.android.com/identity/data/testingbackup)
on an API 28–30 device and an API 31+ device. Use the dedicated device-transfer
transport where available.

1. Sign in as a test account A, save a trip search, and load data that Firestore caches.
2. Select the appropriate transport with `adb shell bmgr transport <transport>` and
   trigger backup with `adb shell bmgr backupnow com.android.sample`.
3. Reinstall the same APK and allow the transport to restore it. Check that the app
   presents the signed-out screen and that no private content is available offline.
4. Sign in as a different test account B. Check that account A's search and private
   data are absent. Record the device, API, transport, commands, and result.

With these rules, a transport may report that there is no app data to back up.
