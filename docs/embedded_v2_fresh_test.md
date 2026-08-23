# Embedded classifier v2 fresh-session test

Date: 2026-08-23  
Device: Green `18EE26A8`  
Placement: left lower leg, matching the admitted-dataset mounting protocol  
Model: `leg_rf_v2`

## Protocol

These recordings are a fresh generalization holdout. They were not available
during feature selection, model training, or export. Do not add them to
`dataset/raw/own` or retrain on them before the v2 evaluation is frozen.

Each file must complete Android automatic offload and size/CRC32 verification.
Raw files will be copied together to a new dedicated holdout directory after
collection. The current Android `Data` connection does not expose the live
classifier telemetry shown through the separate `Home` connection, even though
firmware continues inference while logging. Therefore predictions will be
reproduced offline from each raw CSV using the parity-checked v2 pipeline. The
selected recording label is the ground truth declared by the operator.

## Sessions

| File | Ground truth / maneuver | Duration target | Transfer | Evaluation |
|---|---|---:|---|---|
| `18ee26a8_sitting_67.csv` | natural sitting with small movements | 60 s | CRC32 verified | 22/22 `sitting` |
| `18ee26a8_lying_68.csv` | lying on back with small movements | 60 s | CRC32 verified | 20/20 `lying` |
| `18ee26a8_walking_69.csv` | deliberately slow natural walking, straight corridor with turns at the ends | 60 s | CRC32 verified | 20/20 `walking` |
| `18ee26a8_walking_70.csv` | normal-pace walking on the same corridor route | 60 s | CRC32 verified | 20/20 `walking` |
| `18ee26a8_walking_71.csv` | normal-pace continuous left-hand circles | 50--60 s | CRC32 verified | 20/20 `walking` |
| `18ee26a8_walking_72.csv` | normal-pace continuous right-hand circles | 50--60 s | CRC32 verified | 19/19 `walking` |
| `18ee26a8_running_73.csv` | deliberately slow jog, corridor route with natural turns | 45--60 s | CRC32 verified | 16/20 `running`, 4/20 `cycling` |
| `18ee26a8_running_74.csv` | normal comfortable run, same corridor route | 45--60 s | CRC32 verified | 20/20 `running` |
| `18ee26a8_cycling_75.csv` | outdoor cycling with phone stored; start/stop while stationary | 90--120 s | CRC32 verified | 37/37 `cycling` |

## Runtime gate before collection

Firmware v2 was uploaded to the serial-confirmed Green device. In two serial
observations lasting about 80 seconds in total, 33 predictions took
65.4--67.4 ms each. No queue overflow, IMU deadline fault, or other sampling
error was reported. With the mounted device, the initial idle prediction while
sitting was `sitting`.

## Frozen evaluation

Before prediction, all nine raw files were accepted without corrections or
exclusions and their SHA-256 values were frozen in
`dataset/curation/embedded_v2_fresh_holdout_2026-08-23.sha256`. The files contain
32,131 samples. Every session has an effective rate of approximately 52 Hz,
only 19/20 ms sample intervals, no interval above 100 ms, no identical
consecutive vector, and no accelerometer or gyroscope clipping.

The frozen v2 model correctly classified 194 of 198 overlapping windows:
accuracy 0.980, balanced accuracy 0.980, and macro F1 0.979. All 9/9 complete
sessions had the correct majority class. The only errors were four slow-jog
windows classified as `cycling`; mean confidence for that session was only
49.7%. Slow walking and both circle directions, which had failed in v1, were
correct for every evaluated window.

This result is evidence of improvement for the tested participant and protocol,
not person-independent performance. The independent experimental units are nine
sessions, not 198 overlapping windows, and the recordings were made on the same
day and in conditions related to the development diagnostics.
