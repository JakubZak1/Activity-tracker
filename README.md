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
- samples IMU data at a fixed interval
- stores CSV logs in external flash
- supports serial commands such as `help`, `status`, `space`, `list`, `read`, `start`, `stop`, `stream on`, `stream off`, and `erase`

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
- `space` print total, used, free, and percentage usage of external flash
- `list` list stored files
- `read /log_0000.csv` print a selected file
- `start` start a new logging session
- `stop` stop logging
- `stream on` mirror live samples to serial
- `stream off` disable live serial mirroring
- `erase` remove managed log files

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
