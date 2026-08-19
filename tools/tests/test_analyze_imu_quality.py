from __future__ import annotations

import csv
import sys
import tempfile
import unittest
from pathlib import Path


TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))

from analyze_imu_quality import EXPECTED_COLUMNS, analyze, resolve_files  # noqa: E402


class AnalyzeImuQualityTest(unittest.TestCase):
    def test_stationary_fixture_reports_gravity_and_timing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "lying_1.csv"
            with path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(EXPECTED_COLUMNS)
                writer.writerow([0, "lying", 0, 0, 1, 0, 0, 0])
                writer.writerow([20, "lying", 0, 0, 1, 0, 0, 0])
                writer.writerow([40, "lying", 0, 0, 1, 0, 0, 0])

            result = analyze(resolve_files([Path(directory)]), stationary=True, window_samples=2)

        self.assertEqual(3, result["total_rows"])
        self.assertEqual(20.0, result["timing_ms"]["mean"])
        self.assertEqual(1.0, result["accel_magnitude_g"]["mean"])
        self.assertTrue(all(result["stationary_checks"].values()))
        self.assertEqual(2, result["identical_consecutive_vectors"])


if __name__ == "__main__":
    unittest.main()
