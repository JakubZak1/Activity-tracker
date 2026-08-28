# IMU calibration records

This directory is separate from the activity dataset. Raw six-position files
must never be treated as `sitting` training sessions even though the current BLE
protocol uses that label while recording them.

Each device has a JSON record containing its immutable hardware ID, source-file
identities, acquisition settings, orientation mapping, and derived
coefficients. Raw files and Android sidecars are retained unchanged under
`raw/<device>/<date>/` for reproducibility. Git history versions profile
updates.

| Alias | Hardware ID | Short ID | Current profile |
|---|---|---|---|
| `xiao_unit_01` | `7F1F9F0D872F1832` | `872F1832` | `xiao_unit_01.json` |
| `xiao_unit_02` | `95D112A518EE26A8` | `18EE26A8` | `xiao_unit_02.json` |

For either device, select the profile by full hardware ID and apply calibration
in physical units and in this order:

```text
corrected_acc[i]  = (raw_acc[i] - accelerometer.offset_g[i])
                    * accelerometer.scale_multiplier[i]
corrected_gyro[i] = raw_gyro[i] - gyroscope.bias_dps[i]
```

The CSV logger intentionally remains raw. The future training pipeline and the
embedded inference preprocessing must load/use the same device-specific
coefficients. Do not calibrate training data without applying the identical
transformation before inference.

Regenerate a profile from an exactly six-file capture with:

```powershell
python tools/calibrate_imu.py `
  calibration/raw/xiao_unit_01/2026-08-21 `
  calibration/xiao_unit_01.json `
  --alias xiao_unit_01 `
  --device-id 7F1F9F0D872F1832 `
  --date 2026-08-21
```

The tool verifies every Android sidecar, byte count, CRC32, timestamp interval,
and hardware ID. It automatically requires one recording for each detected
orientation: `+X`, `-X`, `+Y`, `-Y`, `+Z`, and `-Z`.

The 2026-08-21 profiles are engineering candidates. Their six-position fit is
excellent, but a previous oblique holdout for XIAO1 corrected to approximately
0.986 g. This likely reflects fixture/enclosure movement or imperfectly
opposite placement. Secure each board rigidly in its final enclosure and run a
fresh oblique holdout before freezing the research preprocessing contract.
