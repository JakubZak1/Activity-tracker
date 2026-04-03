# Activity Tracker

Activity Tracker is an embedded project for the Seeed Studio XIAO nRF52840 Sense board. The goal is to collect motion data, detect user activity, and build a lightweight foundation for fitness and movement analysis on-device.

The long-term target is to recognize common activities such as:
- walking
- running
- cycling
- sitting
- lying down

The project is also intended to estimate:
- step count
- activity duration
- calories burned

## Hardware

This project targets:
- Seeed Studio XIAO nRF52840 Sense

## Current Status

The repository currently contains a minimal PlatformIO project used to verify the board configuration and USB serial communication.

At the moment, the firmware:
- initializes USB serial
- prints a startup message
- sends a periodic `tick` message once per second

This provides a clean starting point for adding sensor acquisition, activity classification, and local data logging.

## Project Structure

- `src/` - main application source files
- `include/` - project header files
- `lib/` - local libraries
- `test/` - test code and test-related files
- `platformio.ini` - PlatformIO project configuration

## Requirements

To build the project, you need:
- Visual Studio Code with the PlatformIO IDE extension, or
- PlatformIO Core installed locally

## Build

From the project root, run:

```bash
pio run
```

You can also use the Build action from PlatformIO IDE.

## Upload

To upload the firmware to the board:

```bash
pio run --target upload
```

If upload does not start immediately, the board may need to be placed into bootloader mode first.

## Serial Monitor

To open the serial monitor:

```bash
pio device monitor
```

The current monitor speed is:

```ini
monitor_speed = 115200
```

## Notes

The current configuration enables TinyUSB support through:

```ini
build_flags =
  -D USE_TINYUSB
```

This is required for proper USB CDC `Serial` support with the current board and framework setup.

## Roadmap

Possible next development steps:
1. read motion data from onboard sensors
2. preprocess and filter raw measurements
3. detect and classify physical activities
4. count steps
5. estimate active time and calories burned
6. store activity data locally
7. export data for visualization or further analysis

## License

No license has been added yet.
