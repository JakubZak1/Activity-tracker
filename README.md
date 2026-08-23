# Activity Tracker

Activity Tracker is an embedded motion-tracking project for the Seeed Studio XIAO nRF52840 Sense. The current milestone is a reliable BLE-controlled workflow for recording labeled IMU sessions, storing them in QSPI flash, and downloading verified CSV files to an Android phone.

The repository contains firmware, an Android/Kotlin app, a two-placement five-class research dataset workflow, Python ML utilities, and software-only test paths. BLE dataset protocol v6 is the current source-compatible pair: the firmware and Android app must be upgraded together.

Current project status:

- BLE v6 adds stable per-board IDs, RGB identification, collision-free filenames, and simultaneous wrist/leg collection to the v5 loss-safe segmented recording workflow.
- The Android `Data` screen is the primary interface for selecting an activity, starting and stopping recording, and recovering CSV files.
- A v6 mock device simulates segmentation and the dataset workflow when the board is unavailable.
- A one-hour locked-screen v5 run completed six pause/offload/CRC/delete/resume cycles and a final Stop/offload. A later long FIFO test exposed word-pattern desynchronization, so those FIFO recordings are diagnostic only. The replacement acquisition runs complete 104 Hz output-register reads in a dedicated high-priority task, averages adjacent pairs to 52 Hz, preallocates each QSPI segment, and stops rather than accepting a raw-frame gap above 22 ms. Its first 30.634 s stationary hardware run produced 1594 valid rows at 52.001 Hz with a maximum raw-frame interval of 9.766 ms; longer and dynamic validation remains pending.
- A single-subject research dataset has been collected simultaneously from the left wrist and left leg for `walking`, `running`, `cycling`, `sitting`, and `lying`. Raw CSV files remain local and ignored by Git; their immutable curation rules are tracked under `dataset/curation/`.
- Separate six-position engineering calibration profiles for both boards are archived under `calibration/` and applied during feature preparation. Calibration fixtures are excluded from activity training data.
- The offline pipeline compares Random Forest and RBF SVM with paired-session grouped cross-validation. Current out-of-fold results and limitations are documented in [docs/ml_pipeline.md](docs/ml_pipeline.md).
- A compact leg Random Forest is implemented for Green `18EE26A8`. The cadence-aware v2 model has passed software parity, upload, an initial runtime check, and a nine-session fresh same-participant holdout (macro F1 `0.979`, 9/9 correct session majorities). Its classifier-gated lower-leg step counter has also been checked in eight manually counted 100-step trials: mean absolute error was 3.33% for normal walking, 9.0% for slow walking, and 11.0% for running, with no false steps during 14 combined minutes of sitting, lying, and cycling.
- Thesis-relevant failures, design decisions, limitations, and follow-up tests are maintained in [docs/NOTATKI_DO_PRACY.md](docs/NOTATKI_DO_PRACY.md).
- The final engineering checklist and reproducible verification matrix are in [docs/PROJECT_COMPLETION.md](docs/PROJECT_COMPLETION.md).

## Hardware

Target board:
- Seeed Studio XIAO nRF52840 Sense

Main onboard resources used right now:
- LSM6DS3TR-C IMU
- USB CDC serial via TinyUSB
- external 2 MB QSPI flash for session storage

## Current Firmware Features

Normal firmware environment:

- reads complete 104 Hz accelerometer/gyroscope frames after both data-ready
  bits are asserted, averages adjacent pairs, and logs a deterministic 52 Hz stream
- preallocates each 1536 KiB segment before acquisition so FAT cluster
  allocation cannot stall the sampling loop
- runs IMU acquisition in a bounded, higher-priority task so physical QSPI
  erase stalls cannot block sensor reads; queue overflow is a hard fault
- avoids blocking periodic filesystem sync during sampling; durable sync,
  close, reread, and CRC verification occur when a segment is finalized
- boots idle and never creates a dataset session without an explicit start command
- records one of five labels: `walking`, `running`, `cycling`, `sitting`, or `lying`
- writes an active session to a temporary file and exposes only finalized CSV files as complete logs
- keeps recording if the BLE connection is lost; reconnecting clients reconcile state with `status`
- provides BLE v6 status, recording, paused-offload, catalog, resumable download, cancel, and guarded delete operations
- closes the active CSV at 1536 KiB (or before the reserve is exhausted), pauses for verified offload, and resumes the same label only after guarded deletion
- calculates IEEE CRC-32 for finalized CSV bytes
- reports approximate LiPo battery voltage and percentage
- publishes live activity/confidence telemetry from the calibrated leg model on Green; Blue reports `unknown` because it has no deployed wrist model
- detects lower-leg gyroscope peaks and commits them as steps only for classifier windows identified as walking or running
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

## BLE Dataset Protocol v6

The authoritative wire contract is [docs/ble_protocol_v6.md](docs/ble_protocol_v6.md). Protocol v6 is not wire-compatible with earlier prototypes. Firmware and Android must use the same version.

The board advertises as:

```text
ActivityTracker-XXXXXXXX
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
2. Connect to the board and wait for the v6 `hello` capability check and `status` reconciliation.
3. Select exactly one activity label and tap `Start`. Android sends one atomic `record_start` command containing the label.
4. Record the activity. Losing the BLE connection does not stop the board; after reconnect, Android requests `status` and restores the visible recording state.
5. At 1536 KiB, or earlier to protect the storage reserve, firmware finalizes the segment and pauses sampling.
6. A foreground Android service automatically downloads or resumes the closed segment, including while the screen is locked.
7. Only after durable local save and a second size/CRC32 verification does Android request guarded deletion; successful deletion makes firmware resume the same label.
8. Tap `Stop` to finalize, offload, and safely delete the last segment.

Incomplete files caused by power loss or write/finalization failure are listed as incomplete and are not auto-downloaded or deletable through the normal verified-file flow.

### Security and verification status

BLE is intentionally unauthenticated and unencrypted at the application-protocol level for this laboratory prototype. Any nearby client that knows the UUIDs can attempt commands. Name validation, idle-state checks, metadata matching, and Android confirmation reduce accidental deletion, but they are not access control.

The software-only path is described in [docs/testing_without_hardware.md](docs/testing_without_hardware.md). Single-device timing evidence is recorded in [docs/hardware_validation_v5.md](docs/hardware_validation_v5.md), and the first physical two-device v6 smoke test is recorded in [docs/hardware_validation_v6.md](docs/hardware_validation_v6.md).

## Android MVP App

The Android app is a local, Android-only MVP. It does not use accounts, cloud storage, or a backend.

Current app features:

- BLE scan/connect, v6 handshake, status reconciliation, indications, notifications, and queued command writes
- a `Data` screen for selecting the recorded activity, start/stop, storage status, log catalog, and verified automatic offload
- continuous segmented CSV offload to a user-selected Storage Access Framework folder
- a foreground connected-device service and partial wake lock for locked-screen transfer
- byte-count and CRC32 verification before `.part` is finalized
- a full v6 mock device for pause/offload/resume development without the board
- live activity, confidence, battery, session duration, steps, and calories UI
- MET-based calorie estimate using user weight
- phone GPS preview on the map
- foreground location service for recording sessions while the phone is locked
- OSMDroid map with route segments colored by activity
- grouped stop markers for sitting and lying
- Settings and Debug screens
- protocol/debug events that make request/response failures visible

Current limitations:

- power-loss, transfer-phase disconnect, deliberate CRC corruption, and storage-failure injection remain pending physical tests
- BLE has no pairing, authentication, application-layer encryption, or authorization
- the cadence-aware Green classifier v2 has passed desktop/export/native tests and a fresh same-participant session test, but not a long-duration logger-plus-inference stress test or person-independent evaluation
- Blue has no deployed wrist classifier or step counter; the validated Green counter over-counted slow walking by 9.0% and under-counted running by 11.0% on average
- the current classification results are single-subject, session-grouped estimates and do not establish person-independent generalization
- completed Home sessions, per-activity duration totals, calories, steps, and routes are not yet persisted in a local history; this remains required for the strict thesis scope

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

### v6 simulator without the board

1. Open `Settings`, enable `Mock data source`, and return to `Home`.
2. Tap `Connect mock`.
3. Open `Data` and choose a writable folder.
4. Select a label, start recording, and stop it after a few seconds.
5. Confirm that the simulated log is listed, downloaded automatically, and marked verified only after CRC32 succeeds.
6. Confirm the verified local copy remains and the simulated board copy is deleted automatically; also exercise disconnect/reconnect and cancel/resume.

The simulator verifies Android state management and protocol handling; it does not validate the nRF52840 BLE stack, radio behavior, QSPI flash, sensor sampling, or power-loss handling.

In the final system split:
- firmware classifies activity, measures battery, tracks session duration, and counts steps
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

The v6 integration also requires a host-native test environment:

```powershell
$env:PATH = "C:\msys64\ucrt64\bin;$env:PATH"
pio test -e native_protocol_tests
pio test -e native_classifier_tests
```

Both native suites and Android instrumentation smoke tests are required v6
integration gates. They must not be reported as passed until their environments
and tests are present and the commands exit successfully. Setup and expected
coverage are documented in [docs/testing_without_hardware.md](docs/testing_without_hardware.md).

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

The normal firmware accepts the same newline-delimited v6 control records over
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

BLE v6 dataset labels are limited to:

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
872f1832_walking_0.csv
18ee26a8_running_1.csv
872f1832_sitting_2.csv
```

## CSV Format and Dataset Policy

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

The admitted dataset uses simultaneous recordings from two fixed placements: the left wrist and the left lower leg. Future recordings should follow the same protocol:

- same body side and placement
- same board orientation for each placement
- same strap or mounting method
- one activity per session
- no transitions in the main training sessions
- retain the generated Android sidecar and shared `paired_session_id`
- document corrections and exclusions without rewriting raw recordings

## PC Dataset Tools

The final local recordings live below `dataset/raw/own/`. Raw CSV files are ignored by Git and must not be edited in place. `dataset/curation/curation.json` is the tracked source of truth for admitted corrections, recovered metadata, technical-tail exclusions, and paired cycling stop intervals.

Run a read-only timing, bias, noise, clipping, drift, and stationary-sensor
analysis across one CSV file or a directory:

```powershell
python tools/analyze_imu_quality.py --stationary dataset/raw/phone_validation_v5/walking_hour
```

The stationary pass/fail limits are engineering smoke-test heuristics, not a
calibration certificate. Keep calibration fixtures separate from the research
dataset and its manifest. The controlled six-position fixture and acceptance
checks are documented in [docs/imu_validation_protocol.md](docs/imu_validation_protocol.md).

Local dataset folders:

- `dataset/raw/own/` copied CSV logs from this device
- `dataset/raw/pamap2/` optional PAMAP2 source files
- `dataset/processed/` generated intermediate data
- `dataset/models/` trained model artifacts
- `dataset/results/` metrics, plots, and reports
- `dataset/curation/` immutable curation/provenance rules
- `dataset/processed/session_manifest.csv` generated validated manifest
- `dataset/downloads.csv` download registry used to avoid repeated downloads

Prepare the dataset from the nested day/activity/device folders:

```powershell
.\.venv\Scripts\python.exe tools\prepare_ml_dataset.py
```

This validates every CSV and sidecar, verifies CRC32, applies declared corrections, excludes technical tails, mirrors cycling exclusions across paired devices, applies per-device calibration, and generates 5-second windows (260 samples) with 50% overlap. Outputs are:

- `dataset/processed/session_manifest.csv`
- `dataset/processed/features_wrist.csv`
- `dataset/processed/features_leg.csv`
- `dataset/processed/dataset_summary.json`

Download and verify logs with the Android `Data` screen, then copy the finalized
CSV files from the selected SAF folder to `dataset/raw/own/` on the PC. The app
writes `.part` files, supports resume, and exposes the final CSV only after the
device size and CRC32 both match.

`tools/download_log.py` targets the legacy pre-v3 USB `read` protocol and is not
compatible with the current firmware. BLE v6 intentionally carries file bytes
only through the binary `file_data` characteristic; do not use the legacy tool
for new recordings or as evidence that a v6 transfer was verified.

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

Train and compare both classifiers:

```powershell
.\.venv\Scripts\python.exe tools\train_classifiers.py
```

All overlapping windows from one `paired_session_id` stay in the same fold. Wrist and leg use the exact same deterministic 3-fold assignment. The command saves research models, out-of-fold predictions, metrics, confusion matrices, and comparison plots below `dataset/models/` and `dataset/results/`.

Cross-validation selects the leg Random Forest as the strongest offline candidate (macro F1 `0.957`, balanced accuracy `0.962`). A later frozen-model holdout with one newly recorded session per class produced macro F1 `0.986` for wrist RF, `0.912` for wrist SVM, and `1.000` for both leg models. These are session-independent but not subject-independent results, and the small holdout does not justify a universal 100% claim. See [docs/ml_pipeline.md](docs/ml_pipeline.md) before quoting them.

The embedded Green classifier is a separate deployment experiment. Its v2
candidate uses 27 features, including magnitude-domain cadence, and a
20-tree Random Forest. It was augmented with six explicitly marked development
sessions collected after live failures of v1. Exported float32 predictions match
scikit-learn on all 4828 checked windows, and native C++ also reproduces feature
extraction on raw 260-sample CSV windows. These development sessions are no
longer an untouched test. A subsequently frozen nine-session repeat produced
194/198 correct windows (macro F1 `0.979`) and correct majorities for 9/9
sessions. Four slow-jog windows were still classified as `cycling`. This is
encouraging same-participant evidence, not a person-independent result.

## Notes

TinyUSB support is enabled with:
```ini
build_flags =
  -D USE_TINYUSB
```

This is required for stable USB CDC `Serial` support with this board/framework combination.

The formatter keeps local FATFS sources in `src/fatfs/` because the one-time formatting flow depends on files that are not exposed as normal library headers.

## Roadmap

Remaining release and thesis stages:

1. retain a final read-only checksum backup of the admitted raw dataset
2. review the current curation intervals and document the measurement protocol in the thesis
3. complete a longer logger-plus-inference stability run for the cadence-aware leg Random Forest v2 on Green
4. preserve v2 as the frozen embedded baseline and, if possible, repeat its test on another day or participant
5. complete durable Android Home-session history with per-activity durations, steps, calories, route visualization, and local export
6. commit and tag the tested firmware, Android app, ML pipeline, provenance, and documentation as one reproducible release

## License

No license has been added yet.
