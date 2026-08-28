# Dataset curation

The CSV files below `dataset/raw/own` are immutable source recordings. Corrections and exclusions are declared in `curation.json`; preprocessing never rewrites a raw CSV or its Android sidecar.

The two `walking` recordings that were actually running are represented as explicit provenance-preserving label corrections. Their untouched originals remain in `dataset/raw_backup_2026-08-23 whole dataset i think` and on the phone.

Short technical tails are excluded at file level. Probable stops and non-cycling transitions are excluded as time intervals and propagated to the paired wrist/leg recording using elapsed time within the paired session. A synchronized bicycle bump is intentionally retained because it is a real activity event rather than a sensor fault.

Generated inventories, window features, fold assignments, models and evaluation reports belong in `dataset/processed`, `dataset/models` and `dataset/results`. Those directories are reproducible outputs and remain ignored by Git.

`raw_own_final_2026-08-23.sha256` freezes all 177 non-placeholder source files
under `dataset/raw/own` (82 CSV recordings, 81 Android sidecars, and 14 session
notes). Regenerate it only if the admitted raw source tree is intentionally
replaced; an ordinary pipeline run must not change it.

On 2026-08-23 the same manifest was independently regenerated for
`dataset/dataset_raw_own_backup`; all 177 entries matched bit for bit.
