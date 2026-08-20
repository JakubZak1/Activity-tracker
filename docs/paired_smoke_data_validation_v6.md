# Paired BLE v6 smoke-data validation

Date: 2026-08-20

The four source CSVs and their `.session.json` sidecars were copied from the
Android SAF directory into the local, ignored `dataset/test/` directory. They
are engineering smoke data and are not part of the research dataset.

## Pair integrity

| Activity | Green file | Blue file | Shared paired session | Row difference | Duration difference |
|---|---|---|---|---:|---:|
| running | `18ee26a8_running_0.csv` | `872f1832_running_13.csv` | yes | 14 | 269 ms |
| sitting | `18ee26a8_sitting_1.csv` | `872f1832_sitting_14.csv` | yes | 38 | 731 ms |

Every copied CSV exactly matches the `size_bytes` and CRC32 stored in its
sidecar. Each device has a distinct local `session_id`; the two members of each
pair have the same `paired_session_id`.

## Timing and transport integrity

| File | Rows | Duration | Effective rate | Timestamp intervals | Issues |
|---|---:|---:|---:|---|---|
| `18ee26a8_running_0.csv` | 3,044 | 58.519 s | 52.0002 Hz | only 19/20 ms | none |
| `872f1832_running_13.csv` | 3,030 | 58.250 s | 52.0000 Hz | only 19/20 ms | none |
| `18ee26a8_sitting_1.csv` | 14,144 | 271.981 s | 52.0000 Hz | only 19/20 ms | none |
| `872f1832_sitting_14.csv` | 14,106 | 271.250 s | 52.0000 Hz | only 19/20 ms | none |

Across 34,324 samples there are no intervals below 18 ms, no intervals above
100 ms, no clipping and only three identical consecutive vectors. The file-size
difference is therefore not evidence of missing data. Signed, variable-width
decimal CSV values and the small independent start/stop latency explain it.

## Stationary IMU quality

| Device | Mean acceleration magnitude | Magnitude noise | Mean gyro X/Y/Z (dps) | Gyro axis noise |
|---|---:|---:|---|---|
| Blue `872F1832` | 1.00627 g | 0.00052 g | +0.593 / -2.758 / +0.800 | 0.027-0.031 dps |
| Green `18EE26A8` | 1.02939 g | 0.00060 g | +0.833 / -3.611 / -0.061 | 0.025-0.031 dps |

Both accelerometers are stable and within the engineering 0.95-1.05 g gravity
check. Both gyroscopes are quiet and stable, but their zero-rate biases differ
and exceed the one-degree-per-second smoke threshold on Y. This is a
calibration requirement, not a sampling-integrity failure. Calibration must be
stored and applied separately by full hardware ID in training and embedded
inference preprocessing.

## Conclusion

The two-device BLE v6 data path is suitable for the next calibration stage.
The files are not admissible as activity-class training data: the `running`
pair was intentionally stationary, and the `sitting` pair is a transport smoke
test rather than a controlled participant session.
