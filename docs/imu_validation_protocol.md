# IMU data-quality validation

This procedure validates the measurement chain before any recordings are
admitted to the research dataset. Calibration recordings are engineering test
fixtures: keep them outside the dataset manifest and do not relabel them as
physical activities.

## Historical polling baseline

The XIAO remained stationary while `walking_31.csv` through
`walking_37.csv` were recorded. The activity label is therefore intentionally
not meaningful. Read-only analysis of 160,696 rows found:

- valid columns and numeric values, strictly increasing timestamps, no clipping,
  and no repeated consecutive sensor vectors;
- an effective sampling rate of 48.929 Hz;
- mean acceleration magnitude 1.0188 g with 0.0057 g standard deviation;
- stable gyroscope means of approximately +0.42, -2.67, and +0.85 dps on
  X, Y, and Z respectively;
- 88 rows (0.055%) with gyroscope magnitude above 15 dps despite the stationary
  setup;
- no evidence that those isolated gyroscope impulses coincide with the QSPI
  timing pauses.

The accelerometer result is plausible for a stationary board. The gyroscope
stream is usable, but its stable zero-rate offset should be estimated and
handled explicitly. The isolated impulses should be checked in a controlled
fixture before choosing filtering or rejection rules.

The firmware used for this baseline inherited library defaults that configured the accelerometer for +/-16 g and the
gyroscope for +/-2000 dps, with both sensor output data rates at 416 Hz. The
firmware polls the sensor at approximately 50 Hz. These settings are functional,
but they trade measurement resolution and bandwidth for ranges that are wider
than this project's expected signals. Do not change them midway through a
dataset.

## Current acquisition candidate

The current physical candidate keeps the wide +/-16 g and +/-2000 dps ranges
while range scouting is still in progress. It configures both sensors at 104
Hz, reads one complete gyro+accelerometer frame after both data-ready bits are
asserted, averages adjacent pairs, and writes 52 Hz CSV samples. The logger
preallocates the complete QSPI segment before acquisition to avoid FAT cluster
allocation pauses. Acquisition runs in a dedicated high-priority task and
passes samples to the logger through a bounded queue, allowing it to continue
during physical QSPI sector operations. A raw interval above 22 ms or queue
overflow is a hard fault because data integrity can no longer be guaranteed.

An earlier FIFO candidate initially produced a plausible short stationary
capture, but a longer physical recording later showed shifted/mixed axes and
thousands of gravity and gyro outliers. That CSV and all FIFO experiment files
are invalid diagnostic artifacts, not dataset material. The current candidate
must pass a new stationary and dynamic physical validation before its
configuration is frozen.

The first stationary hardware run of the replacement path completed 30.634 s:
1594 CSV rows at 52.001 Hz, only 19/20 ms timestamps, 3189 raw frames, a 9.766
ms maximum raw-frame interval, and zero deadline misses. The phone copy matched
the board's 92,528-byte file and CRC32 `94EC584E`. Acceleration magnitude was
1.0083 +/- 0.00063 g with no gravity outliers or clipping. The stable gyro Y
bias was -2.741 dps and remains a calibration input, not a data-integrity
failure. This is a smoke test; long-duration and dynamic validation are still
required.

This pair-mean filter is not yet the frozen research configuration. Dynamic
range and motion tests must still confirm the full-scale settings and whether a
stronger low-pass filter is justified. Any final change requires repeating the
six-position calibration.

## Controlled six-position accelerometer test

Use battery power if possible so that a USB cable cannot pull the board. Put the
XIAO on a firm, vibration-free, non-metallic surface. Keep the phone separate
from the fixture and do not touch the board during a recording.

For each position below:

1. Place the board in the stated orientation.
2. Wait at least 10 seconds for the board and sensor temperature to settle.
3. Start a 60-second recording from the phone.
4. Stop it without moving the board, wait for CRC verification, and note the
   generated filename next to the position.
5. Only then move to the next position.

Record all six orientations:

| Fixture ID | Orientation | Filename |
|---|---|---|
| `imu_cal_z_up` | Component side facing up | |
| `imu_cal_z_down` | Component side facing down | |
| `imu_cal_x_up` | One short edge down, X approximately +1 g | |
| `imu_cal_x_down` | Opposite short edge down, X approximately -1 g | |
| `imu_cal_y_up` | One long edge down, Y approximately +1 g | |
| `imu_cal_y_down` | Opposite long edge down, Y approximately -1 g | |

The printed axis marking or the recorded means determine which physical edge is
positive. Swapping the two filenames within an axis is harmless as long as the
orientation is documented correctly.

Use one existing activity label for all six files, for example `sitting`.
The filenames and this fixture table, not that protocol label, are the source of
truth for the calibration test.

## Analysis

Copy the six files into a dedicated directory and run:

```powershell
python tools/analyze_imu_quality.py --stationary path\to\imu-six-position
```

For each stationary file, the expected acceleration vector has one dominant
axis near +1 g or -1 g and the other two axes near zero. Across opposite
orientations, use the means to estimate per-axis offset and scale:

```text
offset = (positive_mean + negative_mean) / 2
half_span = (positive_mean - negative_mean) / 2
scale = 1 / half_span
corrected = (raw - offset) * scale
```

Evaluate these engineering acceptance checks before collecting research data:

- every file has the expected schema, finite values, and increasing timestamps;
- effective sampling rate is close to the configured 52 Hz and there are no
  missing, duplicated, or desynchronized FIFO frames;
- the stationary acceleration magnitude is close to 1 g;
- no axis clips;
- acceleration offset and scale estimates are repeatable;
- gyroscope means are stable during every orientation;
- isolated gyroscope impulses are rare and do not grow after the board warms up.

The analyzer's pass/fail values are smoke-test heuristics, not manufacturer
calibration limits. Keep the raw test files unchanged and save any derived
calibration coefficients with the firmware/configuration version that produced
them.

## Follow-up dynamic smoke test

After the six-position test, mount the XIAO in the intended fixed wrist
orientation. Record 60 seconds at rest and then a short, separate recording with
slow rotations about each axis. Confirm that axes respond with the expected sign,
there is no clipping, and timestamps remain healthy. Only after this check should
the IMU range/ODR configuration and preprocessing rules be frozen for dataset
collection.
