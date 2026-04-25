# Activity Tracker

Activity Tracker is an embedded motion-tracking project for the Seeed Studio XIAO nRF52840 Sense. It is being built as a practical foundation for on-device activity recognition, local data logging, and later mobile visualization.

The current firmware can:
- read accelerometer and gyroscope data from the onboard IMU
- log labeled CSV sessions with timestamps
- store sessions in the onboard external QSPI flash
- expose serial commands for inspecting logs and storage usage
- format external flash with a dedicated one-time formatter firmware

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
- stores CSV logs in external flash
- supports serial commands such as `help`, `status`, `space`, `list`, `read`, `label`, `start`, `stop`, `stream on`, `stream off`, and `erase`
- reports approximate LiPo battery voltage and percentage

Formatter environment:
- initializes and formats the external flash with a FAT filesystem
- intended for first-time setup only

## Project Structure

- `src/` application source files
- `src/fatfs/` local FATFS sources used by the formatter firmware
- `include/` public project headers
- `lib/` optional local private libraries
- `test/` test code and test-related notes
- `platformio.ini` PlatformIO environments and dependencies

Key source modules:
- `app.cpp` main runtime flow and command handling
- `imu_reader.cpp` IMU initialization and sampling
- `data_logger.cpp` external flash logging and file access
- `serial_console.cpp` serial command parsing
- `formatter_main.cpp` one-time external flash formatter

## PlatformIO Environments

Main logger firmware:
```bash
pio run -e seeed_xiao_nrf52840_sense
```

Formatter firmware:
```bash
pio run -e seeed_xiao_nrf52840_sense_formatter
```

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
- `ok,already_formatted`
- or `info,formatting_external_flash` followed by `ok,format_completed`

After that, flash the normal logger firmware again.

## Serial Commands

Main commands in the normal firmware:
- `help` show available commands
- `status` print logging state and current file
- `battery` print battery voltage, approximate percentage, and raw ADC value
- `space` print total, used, free, and percentage usage of external flash
- `list` list stored files
- `read /walking_0000.csv` print a selected file
- `label walking` set the label for the next logging session
- `start` start a new logging session
- `stop` stop logging
- `stream on` mirror live samples to serial
- `stream off` disable live serial mirroring
- `erase` remove managed log files

Labels must use lowercase letters, digits, or underscores only. Examples:
- `walking`
- `running`
- `sitting`
- `lying`
- `cycling`

The firmware starts with the default label `sitting` only when no saved label exists yet. The `label` command saves the selected label in external flash, so the board keeps using it after reset or battery power-up.

To change labels, stop the current session first:

```text
stop
label walking
start
```

Session files are named with the active label and session index, for example:

```text
/walking_0000.csv
/running_0001.csv
/sitting_0002.csv
```

## Data Collection Workflow

Recommended recording flow:

1. Flash the main logger firmware.
2. Open the serial monitor.
3. Stop the automatic default session if needed with `stop`.
4. Set the target label, for example `label walking`.
5. For USB-connected recording, start a fresh session with `start`.
6. For battery recording, turn the device off, disconnect USB, then turn it on with the switch. It will automatically start logging with the saved label.
7. Record one clean activity for 2-5 minutes.
8. Stop the session with `stop` after reconnecting, or turn the device off after a recording if USB is not connected.
9. Check files with `list` and storage with `space`.
10. Recover data with `read /walking_0000.csv`.

Battery workflow example:

```text
stop
label walking
```

Then disconnect USB, turn the device off, mount it on the wrist, and turn it on with the switch. The new session will be named like `/walking_0000.csv`.

CSV sessions use the training-oriented column set:

```csv
timestamp_ms,label,acc_x_g,acc_y_g,acc_z_g,gyro_x_dps,gyro_y_dps,gyro_z_dps
```

Derived values such as roll, pitch, temperature, and IMU address are not stored in the dataset logs. They can be recomputed or inspected separately later if needed.

Battery percentage is estimated from LiPo voltage, so treat it as approximate. The value depends on load, charging state, and battery condition.

For the May prototype dataset, keep recordings consistent:
- same wrist
- same board orientation
- same strap or mounting method
- one activity per session
- no transitions in the main training sessions
- keep a PC-side session manifest with file, label, date, subject ID, duration, and notes

## PC Dataset Tools

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

Download a log directly from the board over USB serial:

```bash
python -m pip install pyserial
python tools/download_log.py --port COM5 --file /walking_0002.csv --output dataset/raw/own/walking_0002.csv
```

Download all CSV logs from the board:

```bash
python tools/download_log.py --port COM5 --all --output-dir dataset/raw/own
```

In `--all` mode, downloaded file names get a timestamp prefix by default, for example:

```text
20260425_213000_walking_0000.csv
```

This keeps PC-side files unique even if the board session counter starts from zero again after `erase`. You can choose your own prefix:

```bash
python tools/download_log.py --port COM5 --all --prefix 20260425_walk_test_01
```

Existing local files are skipped by default to prevent accidental duplicates or overwrites. Use `--overwrite` only when you intentionally want to replace a local copy.

The downloader also keeps `dataset/downloads.csv`. In `--all` mode it skips device files that were already downloaded with the same device filename and byte size. Use `--ignore-registry` only if you intentionally want to download them again under a new PC-side name.

In `--all` mode the script asks the board for `status` and skips the currently open log file by default. This avoids copying a file while the firmware is still writing it. Use `--stop-first` to close the active session before downloading, or `--include-current` only when you intentionally want to copy the active file.

Downloads are written to a temporary `.part` file first, then validated, then renamed to the final CSV path. If a transfer fails, the partial file is removed.

Use `--stop-first` if the board is still logging and you want to close the current file before reading it.

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

Build classical ML feature rows from valid raw logs:

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

## Notes

TinyUSB support is enabled with:
```ini
build_flags =
  -D USE_TINYUSB
```

This is required for stable USB CDC `Serial` support with this board/framework combination.

The formatter keeps local FATFS sources in `src/fatfs/` because the one-time formatting flow depends on files that are not exposed as normal library headers.

## Roadmap

Likely next project stages:
1. collect labeled datasets for multiple activities
2. build a PC-side training pipeline
3. run activity classification on-device in real time
4. store compact activity summaries instead of raw logs in product mode
5. add BLE/mobile sync and visualization

## License

No license has been added yet.
