# BLE v5 physical validation log

Date: 2026-08-19

This log records smoke-test evidence from the XIAO nRF52840 Sense prototype and
the Android phone. These CSV files are engineering fixtures, not research
dataset sessions and not manifest inputs.

## Locked-screen transport result

A nearly 20-minute v4 run kept the phone locked for most of the session. The
foreground Android service maintained BLE, downloaded about 15 segments,
verified each CRC32, and removed the verified board copies. The transport and
locked-screen design passed, but concurrent QSPI reads and writes produced
unacceptable catch-up bursts in the sample timestamps. Protocol v5 therefore
pauses sampling while a finalized segment is offloaded.

## Stale partial recovery

`lying_29.csv` was interrupted after 214,760 of 517,371 bytes. Android's
document provider had appended `.txt` to the metadata filename, so the original
reader treated the pair as incomplete. The fixed reader recognized
`lying_29.csv.part.meta.txt`, resumed at the existing byte offset, finalized
the CSV, and removed both partial artifacts.

Independent local verification:

| File | Bytes | CRC32 | Result |
| --- | ---: | --- | --- |
| `lying_29.csv` | 517,371 | `FEC810C0` | matches firmware identity |

Future sidecars use a binary MIME type so providers do not append `.txt`.
Incomplete or mismatched artifacts are preserved under a `*.stale-N.*` name
instead of being overwritten.

## Sampling timing

### Before removing periodic QSPI sync (`lying_29.csv`)

| Metric | Result |
| --- | ---: |
| rows | 9,237 |
| duration | 241.954 s |
| effective rate | 38.173 Hz |
| median interval | 20 ms |
| minimum / maximum | 20 / 184 ms |
| intervals below 18 ms | 0 |
| intervals above 100 ms | 461 |

The 133-176 ms stalls occurred approximately every 20 samples and matched the
configured `FatFile::sync()` cadence. Periodic sync was removed from the active
sampling path. Segment finalization still requires sync, close, full reread,
and CRC verification.

### After removing periodic QSPI sync (`lying_30.csv`)

| Metric | Result |
| --- | ---: |
| bytes / CRC32 | 639,212 / `66EE2B76` |
| rows / label | 11,413 / `lying` |
| duration | 232.873 s |
| effective rate | 49.005 Hz |
| median / average interval | 20 / 20.406 ms |
| minimum / maximum | 20 / 89 ms |
| intervals in 18-22 ms | 11,219 / 11,412 (98.3%) |
| intervals below 18 ms | 0 |
| intervals above 100 ms | 0 |

There were 164 intervals of at least 40 ms. Most occurred every 73-74 samples
(about 1.49 seconds); 122 were exactly 46 ms. This regularity is consistent
with a 4 KiB QSPI write boundary, but that attribution is an inference rather
than a directly instrumented measurement. Hardware FIFO capture or timestamp-
aware resampling remains a possible data-quality improvement before collecting
the research dataset.

## One-hour segmented locked-screen run

A continuous `walking` session produced `walking_31.csv` through
`walking_37.csv`. The first six segments crossed the 1536 KiB threshold; the
seventh was finalized by the user's Stop action. All seven appeared as final
phone CSV files with no residual `.part` artifact.

| File | Bytes | Rows | In-segment duration | Effective rate | CRC32 |
| --- | ---: | ---: | ---: | ---: | --- |
| `walking_31.csv` | 1,572,889 | 26,936 | 550.405 s | 48.937 Hz | `9E47F696` |
| `walking_32.csv` | 1,572,917 | 26,651 | 544.665 s | 48.929 Hz | `35CD5EEA` |
| `walking_33.csv` | 1,572,901 | 26,656 | 544.872 s | 48.920 Hz | `91BA36C5` |
| `walking_34.csv` | 1,572,895 | 26,658 | 544.877 s | 48.923 Hz | `A079C4E3` |
| `walking_35.csv` | 1,572,900 | 26,661 | 544.893 s | 48.927 Hz | `D1993EFF` |
| `walking_36.csv` | 1,572,897 | 26,659 | 544.744 s | 48.937 Hz | `3899722A` |
| `walking_37.csv` | 28,101 | 475 | 9.652 s | 49.109 Hz | `77264237` |

The first-to-last sample wall time was 3,758.627 s (`1:02:38.627`), matching
the application's approximately 1 h 2 min display. Actual in-segment sampling
time was 3,284.108 s. Six deliberate offload gaps totaled 474.519 s; individual
gaps were 71.898-86.563 s.

Across all segments there were 160,696 rows. Of 160,689 internal intervals,
157,821 were 18-22 ms, none was below 18 ms, six exceeded 100 ms, and the
maximum was 172 ms. The aggregate rate inside segments was 48.929 Hz.

The user noticed that the LED stopped blinking near the end. This corresponded
to the final controlled offload pause, not a logger failure: after the pause,
firmware resumed and recorded 9.652 s into `walking_37.csv` before Stop.

This run physically proved the complete sequence six times:

```text
recording -> paused -> download/resume -> local CRC verification
          -> guarded board delete -> recording -> stop -> final offload
```

## FIFO 104 Hz to pair-averaged 52 Hz smoke test

After the polling baseline, firmware was changed to capture complete 104 Hz
accelerometer/gyroscope frames from the LSM6DS3TR-C continuous FIFO and average
each adjacent pair. The wide +/-16 g and +/-2000 dps ranges remain temporary
until dynamic range scouting is complete.

The first physical stationary run produced 836 serial-mirrored CSV rows:

| Metric | Result |
| --- | ---: |
| effective output rate | 52.002 Hz |
| timestamp intervals | 643 x 19 ms, 192 x 20 ms |
| raw frames / output samples | 1,672 / 836 |
| maximum FIFO backlog | 30 words (5 raw frames) |
| discarded words / FIFO overruns | 0 / 0 |
| acceleration magnitude mean / standard deviation | 1.0105 / 0.00136 g |
| gyroscope magnitude mean / standard deviation | 2.8968 / 0.0707 dps |

The gyroscope means (+0.54, -2.72, +0.82 dps) reproduce the stable zero-rate
bias found in the older polling recordings. Pair averaging reduced stationary
noise, but dynamic tests are still required before the filter and ranges are
frozen.

This result was later invalidated as an acquisition architecture decision. A
three-minute FIFO recording (`sitting_5.csv`) contained shifted/mixed axes:
acceleration magnitude averaged only 0.652 g with 0.359 g standard deviation,
while stationary gyroscope magnitude averaged 80.17 dps. The FIFO has no tags,
and the board/library path did not preserve a reliable word pattern over the
longer test. FIFO experiment files must not enter the dataset. The replacement
candidate uses complete data-ready output-register reads plus QSPI segment
preallocation and remains pending physical validation.

## Dedicated-task data-ready replacement smoke test

The replacement path performs exact 12-byte output-register reads at 104 Hz in
a dedicated high-priority FreeRTOS task, pair-averages to 52 Hz, and sends
samples to the CSV/QSPI logger through a bounded queue. Each segment is
preallocated and any raw interval above 22 ms or queue overflow is a hard
fault.

The first stationary physical run produced `sitting_0.csv`:

| Metric | Result |
| --- | ---: |
| duration / rows | 30.634 s / 1,594 |
| effective output rate | 52.001 Hz |
| timestamp intervals | only 19 or 20 ms |
| raw frames / output samples | 3,189 / 1,594 |
| maximum raw-frame interval | 9.766 ms |
| deadline misses | 0 |
| acceleration magnitude mean / standard deviation | 1.0083 / 0.00063 g |
| gyroscope magnitude mean / standard deviation | 2.9124 / 0.0675 dps |
| gravity/gyro stationary outliers and clipping | 0 |
| board and phone identity | 92,528 bytes / CRC32 `94EC584E` |

The gyro Y mean of -2.741 dps is a stable zero-rate bias to be handled by the
frozen calibration/preprocessing path. This short result validates acquisition
integrity but does not replace the pending long-duration and dynamic tests.

### Dynamic walking smoke test

The subsequent phone-controlled `walking_1.csv` run exercised the same
acquisition path during continuous motion. The phone copy was 495,142 bytes
with CRC32 `24362D98` and SHA-256
`B6AE72BC3F6EE804B70B99461EBE0DD89048930C835CB7AD099C5BE0878D5409`.

| Metric | Result |
| --- | ---: |
| duration / rows | 150.673 s / 7,836 |
| effective output rate | 52.000 Hz |
| timestamp intervals | only 19 or 20 ms |
| acceleration magnitude min / mean / max | 0.423 / 1.111 / 2.413 g |
| gyroscope magnitude mean / maximum | 131.33 / 479.10 dps |
| clipping / identical consecutive vectors | 0 / 0 |
| dominant acceleration / gyro rhythm | 1.367 / 1.374 Hz |
| median dominant rhythm across active 10 s windows | 1.400 Hz |

The matching dominant motion rhythm in both sensors, realistic dynamic ranges,
absence of clipping, and uninterrupted timestamps support correct axis and
timing acquisition. This remains a technical smoke recording and must not be
included in the research dataset. Attachment details and the complete activity
protocol must be recorded for research sessions.

## Six-position calibration candidate

Six static enclosure-face recordings (`sitting_2.csv` through
`sitting_7.csv`) were captured on 2026-08-19 for `xiao_unit_01`. Each file ran
for 63.211-64.057 s at 52 Hz with no timing/schema issues or clipping. Five
seconds were trimmed from both ends before calculating orientation means.

The device-specific correction candidate is:

```text
acc_offset_g       = [-0.0042992594,  0.0007926550,  0.0070584101]
acc_scale          = [ 0.9941095597,  1.0006084618,  0.9877269299]
gyro_bias_dps      = [ 0.4952877547, -2.7270172713,  0.8011157593]

corrected_acc[i]   = (raw_acc[i] - acc_offset_g[i]) * acc_scale[i]
corrected_gyro[i]  = raw_gyro[i] - gyro_bias_dps[i]
```

Across the six trimmed positions, acceleration norm improved from
`1.00622 +/- 0.00721 g` raw to `1.00025 +/- 0.00057 g` corrected, with a
corrected range of `0.99756-1.00253 g`. Gyro bias varied by only
`[0.0093, 0.0043, 0.0082] dps` between orientations. Raw files and exact
identities are archived separately from activity data under
`calibration/raw/xiao_unit_01/2026-08-19`; the machine-readable record is
`calibration/xiao_unit_01.json`.

This is an engineering calibration candidate at unrecorded ambient
temperature, not a temperature-characterized certificate. Raw CSV remains
unchanged. The future training and embedded-inference preprocessing paths must
apply the same device-specific transformation.

## Locked-screen full-segment test with dedicated IMU task

After the acquisition-task and UI-clock fixes, a stationary locked-screen run
produced two consecutive near-maximum segments:

| File | Duration | Rows | Bytes | Effective rate | CRC32 |
| --- | ---: | ---: | ---: | ---: | --- |
| `sitting_10.csv` | 504.077 s | 26,213 | 1,572,888 | 52.000 Hz | `8F0EBB74` |
| `sitting_11.csv` | 500.596 s | 26,032 | 1,562,066 | 52.000 Hz | `729A65A6` |

Across both files, all 52,243 timestamp intervals were 19 or 20 ms. There were
no schema issues, gravity/gyro stationary outliers, or clipping. The test
physically covers sustained acquisition during repeated QSPI sector erases,
automatic pause/offload, local CRC verification, guarded board deletion, and
same-label continuation while the phone is locked.

## Remaining physical work

The core locked-screen segmentation/offload workflow now passes. Separate
later tests still need to cover power loss, disconnect during the paused/file-
transfer phases, deliberate CRC corruption, storage failure, and dynamic
range/filter validation. The current data-ready, pair-averaged 52 Hz path must pass a
long locked-screen segmentation/offload run before research data collection.
