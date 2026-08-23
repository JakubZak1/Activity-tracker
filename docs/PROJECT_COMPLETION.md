# Project completion status

Date: 2026-08-23  
Branch: `feature/ble-dataset-control-v3`

## Implemented thesis scope

- Green `18EE26A8`, fixed to the left lower leg, runs the frozen five-class
  Random Forest v2 and the validated step counter. Home targets its configured
  BLE address, never the first device with a matching advertisement name.
- Blue remains a research wrist device. BLE v6 supports stable identity,
  paired recording, segmentation, reconnect, resumable transfer, exact size and
  CRC32 verification, and deletion only after durable phone verification.
- Home records exact walking/running/cycling/sitting/lying/unknown durations.
  Disconnected or more-than-3-second-old telemetry becomes unknown and adds no
  calories. Total duration is the exact sum of all six buckets.
- User mass is frozen at Start. `MET_v1` calories are deterministic from that
  mass and the five recognized duration buckets. They are an estimate, not a
  medical measurement.
- GPS and notification permission are optional. A Home session without GPS
  still records BLE activity, steps, time and calories.
- SQLite stores transaction checkpoints every 5 seconds and at Stop. Active
  rows found after process death become Interrupted at the last checkpoint.
  Completed and interrupted sessions remain until confirmed manual deletion.
- History shows status, dominant activity, time, steps, calories and export.
  Detail shows frozen mass, six duration bars, unknown explanation, historical
  route, export retry and confirmed internal deletion.
- Completed sessions automatically export summary JSON and route CSV below the
  selected SAF `home_sessions` directory. Header-only route CSV is valid for a
  session without GPS. `.part`, flush, file sync and rename protect final files;
  the internal SQLite copy remains authoritative if permission is missing.
- The complete raw source tree is frozen by
  `dataset/curation/raw_own_final_2026-08-23.sha256` (177 files), and its ignored
  backup was previously confirmed bitwise identical.

The detailed Home contract and architecture diagram are in
[`home_sessions.md`](home_sessions.md).

Reproducible phone screenshots of Home, History, Map and Data are stored in
[`screenshots/`](screenshots/). Their values are explicitly synthetic UI
fixtures and are not reported as experimental measurements.

## Final software verification run

All commands below completed successfully on 2026-08-23:

| Gate | Result |
|---|---:|
| Main firmware build | passed; RAM 27,776 B (11.7%), flash 226,676 B (27.9%) |
| Formatter firmware build | passed; RAM 8,456 B (3.6%), flash 58,724 B (7.2%) |
| Native protocol tests | 11/11 passed |
| Native classifier/step tests | 8/8 passed |
| Python pipeline tests | 7/7 passed |
| Android JVM tests | 50/50 passed |
| Android debug APK | assembled and installed on A063, version 1.0.0 |
| Android on-device tests | 7/7 passed on A063 (Android 15) |

The Android device suite covers single-device reconnect/offload/CRC/delete,
paired-device identity/session handling, SQLite checkpoint/recovery/delete,
JSON and header-only route CSV, and History/detail/no-GPS/retry/delete UI. A
debug-only test host renders over the lock screen, preventing keyguard from
pausing Compose test activities. That validates deterministic UI automation;
it must not be misreported as a physical BLE background-continuity test.

## Evidence already collected

- Green embedded v2 fresh holdout: macro F1 0.979 and correct majority for 9/9
  same-participant sessions. Four slow-jog windows were classified as cycling.
- Step validation: normal walking MAPE 3.33%, slow walking 9.0%, running 11.0%;
  zero false steps in 3 min sitting, 3 min lying and 8 min cycling.
- Physical segmented BLE collection previously ran for about 20 minutes mostly
  with the phone locked and created about 15 verified segments. A later session
  ran for roughly one hour before collection stopped; these are useful
  reliability observations, not a destructive stress certificate.
- Wrist/leg RF and SVM results, confusion matrices, holdout reports, plots,
  curation decisions and calibration profiles are preserved under `dataset/`
  and `docs/` (generated artifacts are intentionally ignored by Git).

## One remaining physical acceptance action

Green was not visible over USB during the final software run. Before claiming
the complete physical release matrix, install the final APK, power Green, and
perform one short sitting Home session including a locked-screen interval. Stop
after unlocking and verify: saved History row, sensible sitting/unknown split,
steps, calories, JSON export and route CSV. No walking, running, cycling or new
dataset collection is required.

## Explicit limitations for the thesis

- one participant; no population-level or person-independent claim;
- final deployed model and step counter only for the left lower leg;
- errors depend on pace and movement domain (slow walking/jogging remain hard);
- no BLE bonding, authentication, or application-layer authorization;
- no destructive power-loss, full-storage, or deliberately corrupted physical
  transfer test after the frozen release;
- no long combined logger-plus-inference stress certificate;
- calories are MET-based estimates, not direct physiological measurements.

These limitations belong in the discussion and future-work sections; they do
not invalidate the demonstrated single-participant laboratory prototype.
