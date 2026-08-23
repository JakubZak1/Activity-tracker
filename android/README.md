# Activity Tracker Android app

Native Android/Kotlin companion app for the Activity Tracker embedded project.
It communicates directly with the XIAO nRF52840 Sense over BLE and stores data
locally; there are no accounts, cloud services, or backend.

The current milestone is BLE dataset protocol v6: two-device continuous segmented IMU
recording, locked-screen resumable downloads, exact size/CRC32 verification, and
automatic deletion of only a durably verified board copy. Firmware and Android
v6 firmware and Android must be upgraded together. See [`../docs/ble_protocol_v6.md`](../docs/ble_protocol_v6.md).

## Current status

Implemented in source:

- BLE scan, GATT connection, MTU request, characteristic subscription, command
  writes, control indications, and file notifications
- v6 `hello` capability check followed by authoritative `status`
- a dedicated `Data` screen for recording and file recovery
- atomic `record_start` and recoverable `record_stop` transactions with request
  IDs and timeouts
- resumable Storage Access Framework downloads through `.part` files
- exact byte-count and IEEE CRC-32 verification before finalizing a CSV
- automatic guarded deletion after a second local size/CRC32 verification
- a `connectedDevice` foreground service and partial wake lock while collection
  or an offload backlog is active
- an interactive mock device that records all five labels and exercises the
  catalog/download/delete workflow, disconnects, timeouts, and CRC corruption
  without the board
- automatic reconnect attempts after 1, 2, 4, 8, and 15 seconds
- a separate phone-side Home session for GPS, duration, and calorie estimation
- six exact duration buckets (five classes plus unknown), a frozen session mass,
  stale-telemetry gating, and deterministic MET_v1 calories
- SQLite transaction checkpoints every 5 seconds, interrupted-session recovery,
  History/detail screens, shared live/historical maps, and safe manual deletion
- automatic SAF JSON and route-CSV export through `.part`, sync and rename
- optional GPS and a foreground connected-device service; denied location does
  not block time, steps or calories
- map preview, Settings, and diagnostics opened from Settings
- JVM tests for payload/protocol parsing, controller behavior, CRC transfer
  decisions, calories, and Home/dataset separation
- an API 35 emulator test covering start, disconnect/reconnect, stop, automatic
  verified download, and automatic guarded deletion against the mock device

Still pending:

- destructive physical fault injection for power loss, corrupt transfer data,
  and exhausted storage
- a long logger-plus-inference stress test and person-independent classifier
  evaluation
- person-independent validation and destructive physical fault injection

Green `18EE26A8` publishes the deployed leg classifier, confidence, summary,
and validated step-counter values. Blue intentionally publishes `unknown` and
has no deployed wrist step counter.

## Project layout

```text
android/app/src/main/
  AndroidManifest.xml
  java/pl/edu/activitytracker/
    app/          dependency container
    ble/          UUIDs, v6 wire codec, and telemetry parsers
    data/         BLE/mock sources, dataset controller, and repository
    domain/       protocol, dataset, activity, route, and calorie models
    gps/          phone location tracker
    permissions/  runtime permission helpers
    session/      phone foreground location service
    storage/      DataStore settings, SQLite sessions, SAF exports and dataset storage
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
2. Connect and wait for protocol v6 handshake/status synchronization.
3. Select `walking`, `running`, `cycling`, `sitting`, or `lying`.
4. Tap `Start`; one atomic `record_start,<id>,<label>` is sent.
5. At 1536 KiB, or earlier to preserve the storage reserve, the board finalizes
   a segment and pauses sampling.
6. Android automatically creates or resumes `<name>.part` and requests the
   remaining bytes, including with the screen locked.
7. The partial is renamed only after durable close, size, and CRC32 checks.
8. Android rereads the final CSV, then automatically requests guarded deletion
   of the exact board identity. Successful deletion makes firmware open the
   next segment with the same label. Tap `Stop` to finalize the last segment and
   end collection.

If BLE is lost while recording, the board continues independently. After the
next connection, Android repeats `hello` and `status` instead of guessing or
blindly repeating a mutating command. A failed or interrupted download leaves
the contiguous partial available for resume.

The complete wire format, response variants, MTU behavior, and error rules are
defined in [`../docs/ble_protocol_v6.md`](../docs/ble_protocol_v6.md).

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

Home sessions track optional phone GPS, six duration buckets, steps, and an
approximate MET-based calorie estimate:

```text
kcal = MET * 3.5 * weight_kg / 200 * minutes
```

`Current activity time` is the uninterrupted firmware value. Home's total is
the exact sum of walking, running, cycling, sitting, lying and unknown
milliseconds measured with a monotonic clock. A recognized class is used only
while connected and with telemetry no older than 3 seconds; all other time is
unknown and adds no calories. The mass is frozen at Start.

They are independent of dataset recording. Every 5 seconds the session and new
route points are transactionally checkpointed in SQLite. Stop performs a final
tick, persists Completed, and exports JSON plus route CSV to `home_sessions` in
the selected SAF folder. Active records found after process death become
Interrupted at their last checkpoint. See
[`../docs/home_sessions.md`](../docs/home_sessions.md).

## Permissions and security

The app requests nearby-device Bluetooth permissions for BLE. Location and
Android 13+ notification permission are optional for Home: denial results in a
session without GPS, while BLE activity, duration, steps, calories and local
history continue.

Protocol v6 is intentionally unauthenticated for this laboratory prototype.
Any nearby client that knows the UUIDs can attempt commands; filename and file
identity guards prevent accidents but are not access control. Pairing/bonding or
application-layer authorization is a later milestone.
