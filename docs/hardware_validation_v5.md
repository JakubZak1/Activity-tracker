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

## Remaining v5 physical gate

`lying_30.csv` was smaller than the 1536 KiB segment threshold. A final locked-
screen run must cross at least one threshold and prove the complete v5 state
sequence:

```text
recording -> paused -> download/resume -> local CRC verification
          -> guarded board delete -> recording -> stop -> final offload
```

After that run, verify that the board catalog is empty, every phone segment has
the expected CRC, and the first segment contains no concurrent-transfer timing
distortion. Power-loss and IMU FIFO work remain separate later tests.
