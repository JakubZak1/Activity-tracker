# BLE v6 two-device hardware validation

Date: 2026-08-20

## Devices

| Slot | Full hardware ID | BLE name | Local directory |
|---|---|---|---|
| Blue | `7F1F9F0D872F1832` | `ActivityTracker-872F1832` | `xiao_872f1832/` |
| Green | `95D112A518EE26A8` | `ActivityTracker-18EE26A8` | `xiao_18ee26a8/` |

Both boards ran the same protocol-v6 firmware image. The RGB `identify`
command selected the expected physical board and color. Both handshakes
reported the required v6 capabilities and authoritative hardware identity.

The Green board initially reported `external_fs_mount_failed`. Its QSPI chip
initialized, but the FAT filesystem did not mount. With explicit user
approval, only that board's external QSPI storage was formatted. The formatter
reported `ok,format_completed`; the normal firmware then booted in `idle` with
2,039,808 free bytes.

## Paired smoke test

The Android phone discovered both advertisements simultaneously and connected
two independent GATT clients. Both slots reached `Ready`.

First paired run:

- Blue: `872f1832_running_13.csv`
- Green: `18ee26a8_running_0.csv`
- both recordings started and stopped from the shared controls;
- downloads were serialized;
- both local files passed byte-count and CRC32 verification;
- each device used its own directory and wrote a `.session.json` sidecar;
- verified board copies were automatically deleted.

Locked-screen paired run:

- Blue: `872f1832_sitting_14.csv`
- Green: `18ee26a8_sitting_1.csv`
- both boards continued recording for approximately three minutes while the
  phone screen was locked;
- after unlock, both Android slots still reported recording;
- shared Stop succeeded;
- both transfers passed CRC32 and created their session sidecars;
- both device catalogs were empty after automatic guarded deletion.

The two CSV byte lengths differed slightly. This is expected because CSV rows
contain variable-width signed decimal values and the two BLE start/stop
transactions are not sample-clock synchronized. Paired validation must compare
row counts, final timestamps and effective sample rates rather than raw file
byte length.

## Result and remaining limits

The v6 two-device collection path passes the initial physical smoke test,
including locked-screen operation. The shared `paired_session_id` provides
experimental correspondence for training and comparing separate wrist and leg
models. It does not provide sample-level clock synchronization and should not
yet be used for sensor-fusion training.

Before collecting the research dataset, run a longer paired trial, validate
both CSVs with the IMU quality tools, fix the mount and orientation, and keep
the smoke recordings separate from research data.
