# IMU calibration records

This directory is separate from the activity dataset. Raw six-position files
must never be treated as `sitting` training sessions even though the current BLE
protocol uses that label while recording them.

Each device has a versioned JSON record containing source-file identities,
acquisition settings, orientation mapping, and derived coefficients. Raw files
are retained unchanged under `raw/<device>/<date>/` for reproducibility.

For `xiao_unit_01`, apply calibration in physical units and in this order:

```text
corrected_acc[i]  = (raw_acc[i] - accelerometer.offset_g[i])
                    * accelerometer.scale_multiplier[i]
corrected_gyro[i] = raw_gyro[i] - gyroscope.bias_dps[i]
```

The CSV logger intentionally remains raw. The future training pipeline and the
embedded inference preprocessing must load/use the same device-specific
coefficients. Do not calibrate training data without applying the identical
transformation before inference.
