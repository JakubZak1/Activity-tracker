from __future__ import annotations

import csv
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np


TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))

from ml_dataset import Calibration, apply_calibration, extract_features, inspect_csv, intervals_overlap  # noqa: E402
from evaluate_holdout import session_majority, summarize as summarize_holdout  # noqa: E402
from train_classifiers import LABELS, make_group_folds  # noqa: E402


class MlDatasetTest(unittest.TestCase):
    def test_device_calibration_uses_training_and_firmware_order(self) -> None:
        calibration = Calibration(
            path=Path("fixture.json"),
            device_id="TEST",
            acc_offset=np.asarray([0.1, -0.2, 0.3]),
            acc_scale=np.asarray([2.0, 3.0, 4.0]),
            gyro_bias=np.asarray([1.0, 2.0, 3.0]),
        )
        raw = np.asarray([[1.1, 0.8, 1.3, 11.0, 22.0, 33.0]])
        corrected = apply_calibration(raw, calibration)
        np.testing.assert_allclose(corrected, [[2.0, 3.0, 4.0, 10.0, 20.0, 30.0]])

    def test_feature_vector_is_finite_for_stationary_window(self) -> None:
        values = np.zeros((260, 6), dtype=float)
        values[:, 2] = 1.0
        features = extract_features(values, 52.0)
        self.assertEqual(98, len(features))
        self.assertTrue(all(np.isfinite(value) for value in features.values()))
        self.assertAlmostEqual(1.0, features["acc_mag_g_mean"])
        self.assertAlmostEqual(0.0, features["acc_dynamic_mag_g_mean"])

    def test_csv_inspection_rejects_non_increasing_timestamp(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.csv"
            with path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(["timestamp_ms", "label", "acc_x_g", "acc_y_g", "acc_z_g", "gyro_x_dps", "gyro_y_dps", "gyro_z_dps"])
                writer.writerow([10, "walking", 0, 0, 1, 0, 0, 0])
                writer.writerow([10, "walking", 0, 0, 1, 0, 0, 0])
            _, _, _, _, issues = inspect_csv(path)
        self.assertTrue(any("not strictly increasing" in issue for issue in issues))

    def test_interval_overlap_is_conservative(self) -> None:
        intervals = [(10.0, 15.0, "stop")]
        self.assertTrue(intervals_overlap(7.5, 12.5, intervals))
        self.assertFalse(intervals_overlap(5.0, 10.0, intervals))

    def test_group_folds_are_shared_and_label_stratified(self) -> None:
        datasets = {}
        for placement in ("wrist", "leg"):
            labels = []
            groups = []
            for label in LABELS:
                for index in range(3):
                    labels.append(label)
                    groups.append(f"{label}-{index}")
            row_count = len(labels)
            datasets[placement] = (
                np.zeros((row_count, 1)),
                np.asarray(labels),
                np.asarray(groups),
                ["feature"],
                [],
            )
        assignments = make_group_folds(datasets, folds=3, seed=42)
        self.assertEqual(15, len(assignments))
        for fold in range(3):
            fold_labels = {group.rsplit("-", 1)[0] for group, assigned in assignments.items() if assigned == fold}
            self.assertEqual(set(LABELS), fold_labels)

    def test_holdout_summary_and_session_majority_do_not_fit_models(self) -> None:
        truth = np.asarray(LABELS)
        metrics = summarize_holdout(truth, truth.copy())
        self.assertTrue(metrics["complete_five_class_holdout"])
        self.assertEqual(1.0, metrics["macro_f1_five_class"])
        rows = [
            {"paired_session_id": f"group-{index}", "label": label}
            for index, label in enumerate(LABELS)
        ]
        majority = session_majority(rows, truth.copy())
        self.assertEqual(5, majority["correct"])
        self.assertEqual(1.0, majority["accuracy"])


if __name__ == "__main__":
    unittest.main()
