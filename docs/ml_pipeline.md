# Offline activity-classification pipeline

## Scope

The current experiment classifies five activities (`walking`, `running`, `cycling`, `sitting`, and `lying`) for one participant. Two XIAO nRF52840 Sense boards recorded simultaneously at 52 Hz:

- `872F1832`: left wrist;
- `18EE26A8`: left lower leg.

The objective is to compare sensor placement and two classical classifiers. These results do not yet represent embedded inference and must not be described as person-independent accuracy.

## Data integrity and curation

Raw CSV files under `dataset/raw/own` are immutable. The Android `.session.json` sidecars provide device identity, placement, label, CRC32, size, and `paired_session_id`. `tools/prepare_ml_dataset.py` recursively validates the CSV structure, monotonically increasing timestamps, finite values, labels, sidecars, byte size, and CRC32.

All exceptions are explicit in `dataset/curation/curation.json`:

- one simultaneously recorded pair was accidentally started as `walking` during real running and is admitted as `running` with the original filename, label, size, and CRC retained as provenance;
- one healthy wrist running file lost its sidecar and is paired explicitly with its simultaneous leg file;
- nine very short technical tails are excluded;
- probable cycling stops/transitions are excluded as time ranges and propagated to both placements using elapsed time in their shared paired session;
- a strong event visible simultaneously on both cycling sensors is retained as a real bicycle bump.

The calibration fixtures under `calibration/raw` are never part of the activity dataset.

## Preprocessing and features

Each device uses its own six-position calibration profile:

```text
corrected_acc = (raw_acc - offset_g) * scale_multiplier
corrected_gyro = raw_gyro - bias_dps
```

The first and last 5 seconds of every physical CSV segment are omitted. Remaining signals are split into 5-second windows (260 samples) with 50% overlap. A window intersecting a curated exclusion interval is discarded.

The 98 features include axis and magnitude statistics (mean, standard deviation, extrema, range, RMS, median, IQR, and energy), acceleration/gyroscope signal-magnitude area, axis correlations, and magnitude-domain dominant frequency, spectral energy, and normalized spectral entropy. The same feature order is saved with each research model.

## Evaluation protocol

Random Forest and RBF SVM use fixed, declared configurations and class weighting. Evaluation uses deterministic stratified 3-fold cross-validation over unique `paired_session_id` values:

- no windows from a physical session occur in both training and test data;
- overlapping neighboring windows never leak across folds;
- wrist and leg use identical paired-session fold assignments;
- each test fold contains all five activities.

This is substantially stricter than a random window split. It still uses one participant and recordings collected over a short period, so it measures generalization to unseen sessions of that participant, not to unseen people.

## Current results

| Placement | Model | Macro F1 | Balanced accuracy | Accuracy |
|---|---:|---:|---:|---:|
| wrist | Random Forest | 0.855 | 0.866 | 0.855 |
| wrist | RBF SVM | 0.874 | 0.877 | 0.870 |
| leg | Random Forest | **0.957** | **0.962** | **0.957** |
| leg | RBF SVM | 0.939 | 0.946 | 0.941 |

The leg Random Forest is the strongest current candidate. The wrist models recognize dynamic activities well but confuse `sitting` and `lying`, which can produce similar arm poses. The leg Random Forest separates the two stationary classes much more reliably. Fold-to-fold variation remains important, especially with only three cycling sessions.

## Frozen-model holdout

After the four final models had been trained and saved, both boards were removed/re-mounted and one new paired session was recorded for every activity. These session IDs have zero overlap with the training dataset. The evaluator loads the existing `.joblib` artifacts and never calls `fit`, tunes parameters, or adds holdout windows to the training set.

| Placement | Model | Windows | Macro F1 | Balanced accuracy | Accuracy |
|---|---:|---:|---:|---:|---:|
| wrist | Random Forest | 407 | 0.986 | 0.986 | 0.988 |
| wrist | RBF SVM | 407 | 0.912 | 0.915 | 0.926 |
| leg | Random Forest | 408 | **1.000** | **1.000** | **1.000** |
| leg | RBF SVM | 408 | **1.000** | **1.000** | **1.000** |

For wrist RF, all dynamic and sitting windows were correct; 5 of 70 lying windows were classified as sitting. Wrist SVM classified 29 of 70 lying windows as sitting and one cycling window as sitting. Both leg models classified every holdout window correctly.

This strengthens the conclusion that the lower-leg placement is preferable for this participant and mounting protocol. It does not establish 100% accuracy for other users or arbitrary remounting: the holdout contains only one session per class, adjacent 50%-overlapping windows are correlated, and it was collected by the same participant on the same day as part of the same study. The defensible thesis result should present both grouped cross-validation and this limited post-training holdout.

Generated evidence is stored locally in:

- `dataset/results/model_comparison.png`;
- `dataset/results/confusion_matrices.png`;
- `dataset/results/metrics.json` and `metrics_summary.csv`;
- `dataset/results/oof_predictions_wrist.csv` and `oof_predictions_leg.csv`;
- `dataset/processed/fold_assignments.csv`.
- `dataset/results/holdout/report.md`, `confusion_matrices.png`, `cv_vs_holdout.png`, and per-window prediction CSV files.

## Reproduction

From the repository root:

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe tools\prepare_ml_dataset.py
.\.venv\Scripts\python.exe tools\train_classifiers.py
.\.venv\Scripts\python.exe tools\prepare_ml_dataset.py --raw-dir dataset\raw\holdout --curation dataset\curation\holdout.json --output-dir dataset\processed\holdout
.\.venv\Scripts\python.exe tools\evaluate_holdout.py
.\.venv\Scripts\python.exe -m unittest discover -s tools\tests -v
```

## Embedded leg classifier

The 98-feature desktop models remain the primary RF-versus-SVM research
comparison. Embedded deployment is a separate engineering experiment with a
smaller Random Forest and a reduced feature contract.

The first embedded version used 36 basic time-domain features and reproduced
the desktop export exactly, but live tests exposed a domain shift: slow walking,
slow running, and some turning directions were often classified as `cycling`.
Six follow-up recordings (68 windows) reproduced those failures offline. They
were then designated as development data, so they cannot also be reported as a
final untouched test.

The v2 candidate uses 27 features: mean and standard deviation of all six raw
axes plus mean, standard deviation, minimum, maximum, and dominant frequency
for acceleration magnitude, dynamic acceleration magnitude `abs(|a|-1)`, and
gyroscope magnitude. Its Random Forest has 20 trees, maximum depth 8, and
`min_samples_leaf=2`. The six development sessions are added only to the final
embedded training set; the original grouped evaluation folds remain test-only.

| v2 engineering check | Windows | Macro F1 | Balanced accuracy | Accuracy |
|---|---:|---:|---:|---:|
| leave-one-development-file-out | 68 | 0.844 | 0.745 | 0.721 |
| original grouped CV, development data training-only | 4352 | 0.961 | 0.965 | 0.960 |
| old frozen holdout regression | 408 | 0.996 | 0.996 | 0.995 |

The leave-one-file-out result gives the correct session-majority class for five
of six development recordings. The withheld slow-walking recording remains a
complete failure because it is the only example of that exact variant. This is
the main reason a new physical test is mandatory rather than treating the
perfect development-set resubstitution score as evidence.

The exported v2 forest contains 1312 nodes. Its float32 C++ representation and
scikit-learn agree on all 4352 original training windows, 408 old-holdout
windows, and 68 development windows. Native tests additionally reproduce the
27 Python features and expected class from 11 raw 260-sample windows. A firmware
build used 27,608 of 237,568 bytes RAM (11.6%) and 225,380 of 811,008 bytes flash
(27.8%) before the step counter was added. The current complete firmware uses
27,776 bytes RAM (11.7%) and 226,676 bytes flash (27.9%). The first physical
Green check observed 33 predictions over about 80 seconds. Computation took
65.4--67.4 ms per window and produced no queue, deadline, or sampling fault.
This is an initial runtime gate, not a long-duration stress test. The
subsequently completed fresh-session evaluation is reported below.

Reproduce the deployment artifacts and engineering evaluation with:

```powershell
.\.venv\Scripts\python.exe tools\train_embedded_model_v2.py
.\.venv\Scripts\python.exe tools\export_embedded_model.py
```

Detailed generated evidence is kept in `dataset/results/embedded_v2/` and the
diagnostic provenance in `dataset/curation/live_diagnostic_2026-08-23.json`.

## Fresh embedded-v2 holdout

After v2 training, export, parity tests, firmware build, and upload were all
complete, nine additional Green sessions were recorded without inspecting
intermediate model results. Their raw SHA-256 values were frozen before the
saved model was evaluated. The set covers natural sitting, lying, deliberately
slow and normal walking, continuous circles in both directions, deliberately
slow and normal running, and outdoor cycling.

| Windows | Accuracy | Balanced accuracy | Macro F1 | Correct session majorities |
|---:|---:|---:|---:|---:|
| 198 | 0.980 | 0.980 | 0.979 | 9/9 |

All 79 walking windows were correct, including slow walking and both turning
directions. Cycling, sitting, lying, and normal running were also entirely
correct. Four of 20 slow-jog windows were classified as `cycling`; the slow-jog
session nevertheless had a correct `running` majority and only 49.7% mean
confidence. This residual boundary is consistent with the earlier observation
that low-intensity running can resemble cycling in the available feature space.

The nine files contain 32,131 samples and passed size/CRC, label, calibration,
and timing validation without corrections or exclusions. All sample intervals
were 19 or 20 ms, with no clipping or repeated consecutive sensor vector.

This is a genuine post-freeze session holdout, but it remains a same-participant,
same-day experiment in conditions related to the development recordings.
Overlapping windows are correlated, so 198 windows must not be presented as 198
independent trials. The defensible result is 9/9 correct session majorities for
this participant and protocol, with the slow-jog weakness reported explicitly.
Evidence is stored in `dataset/results/embedded_v2_fresh_holdout_2026-08-23/`,
with provenance in `docs/embedded_v2_fresh_test.md` and `dataset/curation/`.
