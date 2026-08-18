# Activity Tracker

Activity Tracker is an embedded motion-tracking project for the Seeed Studio XIAO nRF52840 Sense. The current milestone is a reliable BLE-controlled workflow for recording labeled IMU sessions, storing them in QSPI flash, and downloading verified CSV files to an Android phone.

The repository contains firmware, an Android/Kotlin app, Python data utilities, and software-only test paths. BLE dataset protocol v4 is the current source-compatible pair: the firmware and Android app must be upgraded together.

Current project status:

- BLE v4 continuous segmented recording, resumable offload, CRC32 verification, and guarded automatic deletion are implemented in source.
- The Android `Data` screen is the primary interface for selecting an activity, starting and stopping recording, and recovering CSV files.
- A v4 mock device simulates segmentation and the dataset workflow when the board is unavailable.
- Earlier v3 recording and transfer paths were exercised on the physical prototype; v4 locked-screen segmentation and automatic deletion still require the physical acceptance run.
- There is currently no research dataset. Existing CSV files, if present locally, are smoke-test recordings only.
- There is no trained ML model, no activity-classification inference on the device, and no real step-counting algorithm. Live activity and summary telemetry remain placeholders.

## Hardware

Target board:
- Seeed Studio XIAO nRF52840 Sense

Main onboard resources used right now:
- LSM6DS3TR-C IMU
- USB CDC serial via TinyUSB
- external 2 MB QSPI flash for session storage

## Current Firmware Features

Normal firmware environment:

- samples IMU data at 50 Hz
- boots idle and never creates a dataset session without an explicit start command
- records one of five labels: `walking`, `running`, `cycling`, `sitting`, or `lying`
- writes an active session to a temporary file and exposes only finalized CSV files as complete logs
- keeps recording if the BLE connection is lost; reconnecting clients reconcile state with `status`
- provides BLE v4 status, recording, catalog, resumable download, cancel, and guarded delete operations
- closes the active CSV at 256 KiB and immediately continues in a new segment with the same label
- calculates IEEE CRC-32 for finalized CSV bytes
- reports approximate LiPo battery voltage and percentage
- publishes placeholder activity/summary telemetry until ML inference and step counting exist
- retains serial maintenance commands for development and recovery

Formatter environment:
- initializes and formats the external flash with a FAT filesystem
- intended for first-time setup only

## Project Structure

- `android/` native Android/Kotlin MVP app
- `docs/` protocol, recovery, and verification documentation
- `src/` application source files
- `src/fatfs/` local FATFS sources used by the formatter firmware
- `include/` public project headers
- `lib/` optional local private libraries
- `test/` test code and test-related notes
- `tools/` Python utilities for USB download, validation, plots, and feature experiments
- `platformio.ini` PlatformIO environments and dependencies

Key source modules:
- `app.cpp` main runtime flow and command handling
- `imu_reader.cpp` IMU initialization and sampling
- `data_logger.cpp` external flash logging and file access
- `ble_service.cpp` BLE GATT service, telemetry notifications, and command handling
- `serial_console.cpp` serial command parsing
- `formatter_main.cpp` one-time external flash formatter

## BLE Dataset Protocol v4

The authoritative wire contract is [docs/ble_protocol_v4.md](docs/ble_protocol_v4.md). Protocol v4 is not wire-compatible with earlier prototypes. Firmware and Android must use the same version.

The board advertises as:

```text
ActivityTracker
```

Service UUID:

```text
7b7d0000-8f7a-4f6a-9f4f-1d2c3b4a5000
```

Characteristics:

| Name | UUID | Properties |
| --- | --- | --- |
| `current_activity` | `7b7d0001-8f7a-4f6a-9f4f-1d2c3b4a5000` | read, notify |
| `activity_summary` | `7b7d0002-8f7a-4f6a-9f4f-1d2c3b4a5000` | read, notify |
| `battery` | `7b7d0003-8f7a-4f6a-9f4f-1d2c3b4a5000` | read, notify |
| `command` | `7b7d0004-8f7a-4f6a-9f4f-1d2c3b4a5000` | write with response |
| `control_response` | `7b7d0005-8f7a-4f6a-9f4f-1d2c3b4a5000` | read, indicate |
| `file_data` | `7b7d0006-8f7a-4f6a-9f4f-1d2c3b4a5000` | notify |

Every command and response carries a request ID. Text control records end with `\n` and are reassembled across GATT packets, so correctness does not depend on the requested MTU 247. Binary file frames carry the request ID, byte offset, and contiguous CSV bytes. Android resumes from the verified length of a `.part` file after interruption.

### Phone-controlled recording workflow

1. In Android, open `Data` and choose a destination folder using the system folder picker.
2. Connect to the board and wait for the v4 `hello` capability check and `status` reconciliation.
3. Select exactly one activity label and tap `Start`. Android sends one atomic `record_start` command containing the label.
4. Record the activity. Losing the BLE connection does not stop the board; after reconnect, Android requests `status` and restores the visible recording state.
5. At 256 KiB, firmware finalizes a segment and immediately continues in a new file with the same label.
6. A foreground Android service automatically downloads or resumes each closed segment, including while the screen is locked.
7. Only after durable local save and a second size/CRC32 verification does Android automatically request guarded deletion of the board copy.
8. Tap `Stop` to finalize, offload, and safely delete the last segment.

Incomplete files caused by power loss or write/finalization failure are listed as incomplete and are not auto-downloaded or deletable through the normal verified-file flow.

### Security and verification status

BLE is intentionally unauthenticated and unencrypted at the application-protocol level for this laboratory prototype. Any nearby client that knows the UUIDs can attempt commands. Name validation, idle-state checks, metadata matching, and Android confirmation reduce accidental deletion, but they are not access control.

The v4 code can be exercised without a board as described in [docs/testing_without_hardware.md](docs/testing_without_hardware.md). Physical locked-screen offload, QSPI pressure, and segment-boundary timing remain pending hardware tests.

## Android MVP App

The Android app is a local, Android-only MVP. It does not use accounts, cloud storage, or a backend.

Current app features:

- BLE scan/connect, v4 handshake, status reconciliation, indications, notifications, and queued command writes
- a `Data` screen for selecting the recorded activity, start/stop, storage status, log catalog, and verified automatic offload
- continuous segmented CSV offload to a user-selected Storage Access Framework folder
- a foreground connected-device service and partial wake lock for locked-screen transfer
- byte-count and CRC32 verification before `.part` is finalized
- a full v4 mock device for segmentation/recording/catalog/download/delete development without the board
- live activity, confidence, battery, session duration, steps, and calories UI
- MET-based calorie estimate using user weight
- phone GPS preview on the map
- foreground location service for recording sessions while the phone is locked
- OSMDroid map with route segments colored by activity
- grouped stop markers for sitting and lying
- Settings and Debug screens
- protocol/debug events that make request/response failures visible

Current limitations:

- v4 continuous locked-screen offload has not yet passed the physical acceptance run
- BLE has no pairing, authentication, application-layer encryption, or authorization
- firmware publishes placeholder activity, confidence, steps, and summary values
- there is no ML model or on-device inference
- there is no real dataset from which classification quality could be reported
- product/demo sessions and routes are not yet a complete durable history/export feature

Open the Android app in Android Studio by selecting:

```text
activity_tracker/android
```

Build and run JVM tests from `android/`:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

If `java` is not available in `PATH`, point `JAVA_HOME` at Android Studio's
embedded JDK. With the per-user installation used by this project:

```powershell
$env:JAVA_HOME="$env:LOCALAPPDATA\Programs\android-studio\jbr"
```

If Android Studio was installed in a different location, select its `jbr`
directory instead.

See [android/README.md](android/README.md) for the app architecture and [docs/testing_without_hardware.md](docs/testing_without_hardware.md) for the host and emulator checks.

### v4 simulator without the board

1. Open `Settings`, enable `Mock data source`, and return to `Home`.
2. Tap `Connect mock`.
3. Open `Data` and choose a writable folder.
4. Select a label, start recording, and stop it after a few seconds.
5. Confirm that the simulated log is listed, downloaded automatically, and marked verified only after CRC32 succeeds.
6. Confirm the verified local copy remains and the simulated board copy is deleted automatically; also exercise disconnect/reconnect and cancel/resume.

The simulator verifies Android state management and protocol handling; it does not validate the nRF52840 BLE stack, radio behavior, QSPI flash, sensor sampling, or power-loss handling.

In the final system split:
- firmware classifies activity, measures battery, tracks session duration, and later counts steps
- Android displays activity, estimates calories, collects phone GPS, and visualizes the route

## PlatformIO Environments

Main logger firmware:

```bash
pio run -e seeed_xiao_nrf52840_sense
```

Formatter firmware:

```bash
pio run -e seeed_xiao_nrf52840_sense_formatter
```

The v4 integration also requires a host-native protocol test environment:

```powershell
$env:PATH = "C:\msys64\ucrt64\bin;$env:PATH"
pio test -e native_protocol_tests
```

`native_protocol_tests` and at least one Android emulator smoke test are required integration gates for v4. They must not be reported as passed until their environments/tests are present and the commands complete successfully. Setup, expected coverage, and the `ActivityTracker_API_35` AVD flow are documented in [docs/testing_without_hardware.md](docs/testing_without_hardware.md).

## Upload

Upload the main firmware:
```bash
pio run -e seeed_xiao_nrf52840_sense --target upload
```

Upload the formatter firmware:
```bash
pio run -e seeed_xiao_nrf52840_sense_formatter --target upload
```

If the board does not enter upload mode cleanly, reset it twice to enter the bootloader and try again.

## Serial Monitor

Open the serial monitor:
```bash
pio device monitor
```

Monitor speed:
```ini
monitor_speed = 115200
```

## First-Time External Flash Setup

Before using the logger on a fresh board, format the external flash once:

```bash
pio run -e seeed_xiao_nrf52840_sense_formatter --target upload
pio device monitor
```

Expected output is similar to:
- `warn,force_format_enabled`
- `info,formatting_external_flash`
- `ok,format_completed`

The formatter environment intentionally erases every existing QSPI file. Build
or upload it only when the external flash contents may be discarded.

After that, flash the normal logger firmware again.

## Serial Commands

The normal firmware accepts the same newline-delimited v4 control records over
USB serial as it does over BLE. Choose an unsigned 32-bit request ID for each
command:

- `hello,<id>` report protocol version and capabilities
- `status,<id>` report the authoritative recording state and free space
- `record_start,<id>,<label>` atomically select a label and start recording
- `record_stop,<id>` finalize the active session and report size plus CRC32
- `list,<id>` emit `file` records followed by `list_end`
- `delete,<id>,<name>,<size>,<CRC32>` delete only an exact completed-file identity
- `cancel,<id>` cancel an active BLE catalog/download operation
- `help` print the supported console syntax
- `stream on` mirror live samples to serial
- `stream off` disable live serial mirroring

File download remains BLE-only because its data is carried by the binary
`file_data` characteristic. The console deliberately has no unguarded `erase`
command and no separate persisted `label` command.

BLE v4 dataset labels are limited to:

- `walking`
- `running`
- `cycling`
- `sitting`
- `lying`

The firmware boots idle. Booting, resetting, reconnecting BLE, or disconnecting
USB does not start a recording. To change labels, stop the current session and
start a new atomic recording command:

```text
record_stop,12
record_start,13,walking
```

Finalized session files use a managed label/index name, for example:

```text
walking_0000.csv
running_0001.csv
sitting_0002.csv
```

## CSV Format and Pre-Dataset Policy

Use the Android `Data` workflow described above for phone-controlled recordings.
The serial path uses the same request-ID/state coordinator for diagnostics and
recovery, but resumable binary download and phone-side CRC verification remain
BLE/Android responsibilities.

CSV sessions use the training-oriented column set:

```csv
timestamp_ms,label,acc_x_g,acc_y_g,acc_z_g,gyro_x_dps,gyro_y_dps,gyro_z_dps
```

Derived values such as roll, pitch, temperature, and IMU address are not stored in the dataset logs. They can be recomputed or inspected separately later if needed.

Battery percentage is estimated from LiPo voltage, so treat it as approximate. The value depends on load, charging state, and battery condition.

No research dataset has been collected yet. Before recording data intended for ML, first complete the physical v4 validation, choose a stable mount and orientation, and define the measurement protocol. Future recordings should use:

- same wrist
- same board orientation
- same strap or mounting method
- one activity per session
- no transitions in the main training sessions
- keep a PC-side session manifest with file, label, date, subject ID, duration, and notes

## PC Dataset Tools

These utilities prepare the future data workflow; their presence does not mean that a research dataset or ML result exists. The tracked dataset directories are placeholders, raw CSV files are ignored by Git, and any recovered local recordings must be treated as smoke-test material unless they are deliberately admitted to a documented measurement protocol.

Local dataset folders:

- `dataset/raw/own/` copied CSV logs from this device
- `dataset/raw/pamap2/` optional PAMAP2 source files
- `dataset/processed/` generated intermediate data
- `dataset/models/` trained model artifacts
- `dataset/results/` metrics, plots, and reports
- `dataset/sessions.csv` session manifest
- `dataset/downloads.csv` download registry used to avoid repeated downloads

Raw CSV files are ignored by git. Keep them locally in `dataset/raw/own/` and add one row per recording to `dataset/sessions.csv`:

```csv
file,label,date,subject_id,duration_s,placement,orientation,source,notes
walking_0002.csv,walking,2026-04-25,S01,180,wrist,usb_forward,own,normal pace
```

Validate copied logs and the manifest:

```bash
python tools/validate_dataset.py
```

Add missing valid raw logs to the manifest after downloading a batch:

```bash
python tools/sync_manifest.py --orientation usb_toward_hand --notes "normal pace"
```

Use `--dry-run` first if you want to preview what would be added. The script skips invalid/empty CSV files and existing manifest entries.

Download and verify logs with the Android `Data` screen, then copy the finalized
CSV files from the selected SAF folder to `dataset/raw/own/` on the PC. The app
writes `.part` files, supports resume, and exposes the final CSV only after the
device size and CRC32 both match.

`tools/download_log.py` targets the legacy pre-v3 USB `read` protocol and is not
compatible with the current firmware. BLE v4 intentionally carries file bytes
only through the binary `file_data` characteristic; do not use the legacy tool
for new recordings or as evidence that a v4 transfer was verified.

Load all local raw logs and print a quick summary:

```bash
python tools/dataset_loader.py
```

Plot a single session for a quick sensor sanity check:

```bash
python -m pip install matplotlib
python tools/plot_session.py dataset/raw/own/20260425_213000_walking_0000.csv
```

Plots are saved to:

```text
dataset/results/plots/
```

After a real, validated dataset exists, build classical feature rows from valid raw logs with:

```bash
python tools/build_features.py
```

By default this ignores the first 5 seconds and last 5 seconds of every session, then uses 2 second windows with 50% overlap. This keeps startup/shutdown handling out of the training windows. The output goes to:

```text
dataset/processed/features.csv
```

For tiny smoke-test files only, use a shorter window:

```bash
python tools/build_features.py --window-s 0.04 --overlap 0 --min-samples 2 --trim-start-s 0 --trim-end-s 0
```

There is currently no training/evaluation pipeline, selected classifier, exported embedded model, confusion matrix, or defensible accuracy/F1 result in this repository.

## Notes

TinyUSB support is enabled with:
```ini
build_flags =
  -D USE_TINYUSB
```

This is required for stable USB CDC `Serial` support with this board/framework combination.

The formatter keeps local FATFS sources in `src/fatfs/` because the one-time formatting flow depends on files that are not exposed as normal library headers.

## Roadmap

Next project stages:

1. complete native/JVM/emulator verification of protocol v4
2. validate BLE v4 and QSPI behavior on the repaired physical prototype, including locked-screen segmentation, reconnect, power loss, resume, CRC mismatch, and guarded automatic deletion
3. define one stable wrist mount, orientation, and measurement protocol
4. collect and validate the first real five-class dataset
5. build an offline training/evaluation pipeline with session-level splits
6. select and export a model, then implement on-device inference and smoothing
7. implement and evaluate step counting
8. replace placeholder activity/summary telemetry and complete durable Android session export

## License

No license has been added yet.
