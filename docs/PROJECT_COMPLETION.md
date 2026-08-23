# Project completion status

Date: 2026-08-23  
Branch: `feature/ble-dataset-control-v3`

## Engineering scope completed so far

- Two XIAO nRF52840 Sense devices have stable identities and can record paired
  wrist/leg sessions through BLE v6.
- Five labels are supported: walking, running, cycling, sitting, and lying.
- The logger boots idle, stores temporary/complete files safely, rotates near
  capacity, resumes transfer, verifies size and CRC32, and deletes a board copy
  only after durable phone verification.
- The admitted single-subject dataset, calibration profiles, curation rules,
  Random Forest/SVM comparison, and reproducible feature pipeline exist.
- Green `18EE26A8` runs the frozen lower-leg Random Forest v2. Its fresh
  same-participant holdout achieved macro F1 0.979 and 9/9 correct session
  majorities; this is not a person-independent result.
- The Green step counter has manually counted physical evidence: normal walking
  MAPE 3.33%, slow walking 9.0%, running 11.0%, and zero false steps during 14
  combined minutes of sitting, lying, and cycling.
- The Android Home session displays live activity, confidence, current-activity
  time, total session time, steps, estimated MET calories, battery, GPS, and a
  route map.
- The complete raw source tree is frozen by
  `dataset/curation/raw_own_final_2026-08-23.sha256` (177 files).
- The ignored `dataset/dataset_raw_own_backup` copy was regenerated into a
  temporary manifest and compared with that final manifest; all 177 files were
  bitwise identical.

## Final verification run

All commands below completed successfully on 2026-08-23:

| Gate | Result |
|---|---:|
| Main firmware build | passed; RAM 27,776 B (11.7%), flash 226,676 B (27.9%) |
| Formatter firmware build | passed |
| Native protocol tests | 11/11 passed |
| Native classifier/step tests | 8/8 passed |
| Python pipeline tests | 7/7 passed |
| Android JVM tests | 43/43 passed |
| Android debug APK | built and installed on phone A063 |
| Android on-device simulator UI tests | 2/2 passed on phone A063 (Android 15) |

The UI tests cover the one-device reconnect/offload/CRC/delete flow and paired
two-device identity/session handling. They use simulated devices and do not
replace the already documented physical BLE/IMU tests.

## Required before the final hand-in

1. Complete durable Home-session results: accumulate time separately for each
   recognized activity, save the finished session locally with steps, estimated
   calories and route, and provide a history/detail visualization. The current
   live values alone are not a sufficiently strict interpretation of the thesis
   description.
2. Review the intended source/documentation changes, then commit them as one
   reproducible release and create a tag. The current working tree is still
   intentionally uncommitted.
3. Copy the repository, the ignored raw dataset, generated metrics/plots/models,
   and the checksum manifest to the final backup location. A Git clone alone
   will not contain ignored research data.
4. Write the thesis using the recorded evidence and limitations. Cite sources
   for the IMU, MET formula/values, BLE, algorithms, and evaluation metrics.
5. Prepare a short repeatable demonstration: connect Green, start Home, show a
   prediction/steps/time/calories, then show Data recording and a CRC-verified
   local CSV. Existing physical results may be used; no more exercise dataset is
   required.

After durable session results plus the release and thesis steps, the engineering
project can reasonably be declared complete for its stated laboratory scope.

## Explicit non-blocking limitations

These are valuable future-work items, not reasons to delay the present thesis:

- no independent participant and therefore no claim of population-level
  generalization;
- no deployed wrist classifier or wrist step counter;
- no long-duration combined logger-plus-inference stress test after the final
  v2 model;
- no destructive power-loss, full-storage, or deliberately corrupted physical
  BLE test;
- BLE laboratory mode has no bonding or application-layer authorization;
- calorie values are MET-based estimates, not physiological measurements.
