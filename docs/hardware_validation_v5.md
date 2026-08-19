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

## Remaining physical work

The core locked-screen segmentation/offload workflow now passes. Separate
later tests still need to cover power loss, disconnect during the paused/file-
transfer phases, deliberate CRC corruption, storage failure, and confirmation
of an empty board catalog after the stress run. Hardware FIFO capture or
timestamp-aware resampling remains recommended before research data collection
if tighter than the measured ~49 Hz effective rate is required.
