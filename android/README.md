# Activity Tracker Android app

Native Android/Kotlin companion app for the Activity Tracker embedded project.
It communicates directly with the XIAO nRF52840 Sense over BLE and stores data
locally; there are no accounts, cloud services, or backend.

The current milestone is BLE dataset protocol v3: selecting one of five labels,
starting and stopping an IMU recording, listing board files, resuming downloads,
verifying size plus CRC32, and manually deleting only a verified board copy.
Firmware and Android v3 must be upgraded together.

## Current status

Implemented in source:

- BLE scan, GATT connection, MTU request, characteristic subscription, command
  writes, control indications, and file notifications
- v3 `hello` capability check followed by authoritative `status`
- a dedicated `Data` screen for recording and file recovery
- atomic `record_start` and recoverable `record_stop` transactions with request
  IDs and timeouts
- resumable Storage Access Framework downloads through `.part` files
- exact byte-count and IEEE CRC-32 verification before finalizing a CSV
- explicit delete confirmation, enabled only for a locally verified identity
- an interactive mock device that records all five labels and exercises the
  catalog/download/delete workflow, disconnects, timeouts, and CRC corruption
  without the board
- automatic reconnect attempts after 1, 2, 4, 8, and 15 seconds
- a separate phone-side Home session for GPS, duration, and calorie estimation
- map preview, foreground location service, Settings, and raw Debug events
- JVM tests for payload/protocol parsing, controller behavior, CRC transfer
  decisions, calories, and Home/dataset separation
- an API 35 emulator test covering start, disconnect/reconnect, stop, automatic
  verified download, and confirmed deletion against the mock device

Still pending:

- physical BLE, QSPI, disconnect, power-loss, and throughput acceptance tests
- a real research dataset, trained classifier, embedded inference, and a real
  step-counting algorithm
- durable product-session history/export beyond the dataset CSV workflow

Live activity, confidence, summary, and step values from the current firmware
remain placeholders until the later ML milestone.

## Project layout

```text
android/app/src/main/
  AndroidManifest.xml
  java/pl/edu/activitytracker/
    app/          dependency container
    ble/          UUIDs, v3 codec, and telemetry parsers
    data/         BLE/mock sources, dataset controller, and repository
    domain/       protocol, dataset, activity, route, and calorie models
    gps/          phone location tracker
    permissions/  runtime permission helpers
    session/      phone foreground location service
    storage/      DataStore settings and SAF `.part`/CRC file storage
    ui/           Compose screens and navigation
android/app/src/test/
  JVM protocol and controller tests
android/app/src/androidTest/
  Compose integration test against the mock device
```

`DatasetController` owns BLE dataset transactions and their state. The normal
Home session is deliberately phone-side: its Start, Stop, and Reset actions do
not start or stop the board's dataset logger.

## Open, build, and test

Open `activity_tracker/android` as the Android Studio project. Android Studio
should use its embedded JDK.

From this directory on Windows:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --console=plain
```

If `java` is unavailable in the terminal, point `JAVA_HOME` at the installed
Android Studio runtime. For the per-user installation used during recovery:

```powershell
$env:JAVA_HOME="$env:LOCALAPPDATA\Programs\android-studio\jbr"
```

The debug APK is generated under:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The required emulator and physical acceptance gates are described in
[`../docs/testing_without_hardware.md`](../docs/testing_without_hardware.md).
Run the existing emulator test with `connectedDebugAndroidTest`; a `NO-SOURCE`
result is not an accepted pass.

## BLE dataset workflow

1. Open `Data` and choose a writable destination with the system folder picker.
2. Connect and wait for protocol v3 handshake/status synchronization.
3. Select `walking`, `running`, `cycling`, `sitting`, or `lying`.
4. Tap `Start`; one atomic `record_start,<id>,<label>` is sent.
5. Tap `Stop`; the board finalizes the CSV and returns its name, size, and CRC32.
6. Android automatically creates or resumes `<name>.part` and requests the
   remaining bytes from its current length.
7. The partial is renamed to `<name>` only after size and CRC32 both match.
8. Deleting the board copy remains a separate, confirmed user action.

If BLE is lost while recording, the board continues independently. After the
next connection, Android repeats `hello` and `status` instead of guessing or
blindly repeating a mutating command. A failed or interrupted download leaves
the contiguous partial available for resume.

The complete wire format, response variants, MTU behavior, and error rules are
defined in [`../docs/ble_protocol_v3.md`](../docs/ble_protocol_v3.md).

## Local file safety

The selected folder is retained as a persistable SAF tree URI. Each partial has
a sidecar containing protocol version, device identity, remote name, size, and
CRC32. A partial belonging to different metadata is never appended to.

The final file is considered verified only when:

- the remote name is a managed dataset CSV name;
- the received byte count equals the declared unsigned 32-bit size;
- CRC32 over the exact local bytes equals the uppercase value from firmware.

Remote deletion repeats the exact name, size, and CRC32. The firmware validates
that identity again, so the confirmation dialog is not the only guard.

## Mock workflow without the board

1. Enable `Mock data source` in Settings.
2. Tap `Connect mock` and open `Data`.
3. Choose a folder, select a label, then Start and Stop a short recording.
4. Confirm automatic download and local CRC verification.
5. Disconnect/reconnect during a recording to exercise status reconciliation.
6. Confirm deletion is unavailable before verification and still requires the
   dialog afterwards.

The mock verifies Android state management, not the nRF52840 radio, BLE stack,
QSPI flash, IMU timing, or power-loss behavior.

## Home, map, and calories

Home sessions track phone GPS, duration, and an approximate MET-based calorie
estimate:

```text
kcal = MET * 3.5 * weight_kg / 200 * minutes
```

They are independent of dataset recording. The map renders walking, running,
and cycling as colored route segments and groups stationary sitting/lying
points. These product-facing features do not yet form a durable session-history
or thesis dataset pipeline.

## Permissions and security

The app requests nearby-device Bluetooth permissions for BLE. Location is used
for the map and phone session, and Android 13+ requires notification permission
for the foreground location service.

Protocol v3 is intentionally unauthenticated for this laboratory prototype.
Any nearby client that knows the UUIDs can attempt commands; filename and file
identity guards prevent accidents but are not access control. Pairing/bonding or
application-layer authorization is a later milestone.
