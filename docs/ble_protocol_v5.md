# Activity Tracker BLE dataset protocol v5

Protocol v5 replaces concurrent sampling and QSPI transfer from v4 with an
explicit, loss-safe offload pause. Firmware and Android must be upgraded
together. The GATT UUIDs, newline framing, request IDs, MTU-independent
fragmentation, binary file frames, timeouts, and CRC32 identity rules remain
unchanged.

## Version and capabilities

```text
hello,<request_id>
ok,<request_id>,hello,5,recording;catalog;download;resume;crc32;segmentation;auto_offload;pause_offload;imu_drdy104_mean2_52_deadline_guard
```

Client request IDs are `1..4294967295`; `0` is reserved for asynchronous
firmware faults. Labels are `walking`, `running`, `cycling`, `sitting`, and
`lying`.

## Recording and offload state

The recording machine has four states: `idle`, `recording`, `paused`, and
`fault`.

- `record_start,<id>,<label>` starts one logical recording. The logger first
  preallocates the full QSPI segment. It then reads complete 104 Hz
  accelerometer/gyroscope output-register frames in a dedicated high-priority
  task after both data-ready bits are asserted and averages each adjacent pair
  into one 52 Hz CSV sample. A bounded RTOS queue decouples acquisition from
  slower CSV/QSPI writes; queue overflow is a hard fault.
- While `recording`, only `hello`, `status`, idempotent `record_start`, and
  `record_stop` are accepted. QSPI catalog reads, CRC scans, downloads, and
  deletion cannot compete with IMU sampling.
- CSV timestamps follow a rational 52 Hz sequence (19 or 20 ms intervals).
  A raw-frame interval above 22 ms or an exact-read failure enters `fault`
  instead of silently accepting a missing or torn sample.
- The active file is not periodically `sync()`ed during sampling because a
  physical measurement showed that one QSPI sync blocks the loop for roughly
  110-160 ms. Finalization still requires successful sync, close, full reread,
  and CRC verification. A power loss leaves the active `.part` incomplete and
  may lose its last filesystem-buffered bytes; it is never presented or
  deleted as a verified CSV.
- At 1536 KiB, or earlier when needed to retain the 64 KiB storage reserve,
  firmware syncs, closes, rereads, CRC-verifies, and finalizes the active
  segment. It then enters `paused` without opening another file.
- Android downloads or resumes the closed segment, durably saves it, verifies
  its byte count and CRC32, reopens it for a second verification, and sends the
  guarded `delete` command.
- Firmware recalculates the board copy's CRC. Only successful deletion of the
  exact pending segment opens the next `.part` file with the same label and
  returns to `recording`.
- `record_stop` is valid in both `recording` and `paused`. In the paused case it
  ends the logical session while retaining the already finalized segment for
  recovery.

This design intentionally creates a visible gap between consecutive CSV
segments while BLE offload is in progress. It avoids the bursty and delayed
50 Hz samples observed when v4 read and hashed QSPI concurrently with logging.

## Status records

```text
status,<id>,idle,<last_name|none>,<size>,<crc32|none>,<free_bytes>
status,<id>,recording,<label>,<active_name>,<elapsed_ms>,<bytes_written>,<estimated_free_bytes>
status,<id>,paused,<label>,<closed_name>,<size>,<crc32>,<elapsed_ms>,<free_bytes>
status,<id>,fault,<code>,<active_name|none>,<free_bytes>
```

During `recording`, free space is estimated from the value measured before the
segment was opened so `status` does not perform a filesystem scan. During
`paused`, `status` identifies the exact segment that must be offloaded before
automatic resume.

## Locked-screen and failure behavior

Android runs a `connectedDevice` foreground service and holds a partial wake
lock while continuous collection or an offload backlog exists. Reconnect
attempts occur after 1, 2, 4, 8, and 15 seconds. A locked phone may therefore
complete the pause/offload/delete/resume cycle, subject to normal Android and
vendor battery-management policies.

If BLE is unavailable during `recording`, sampling continues until the segment
limit. The board then remains safely `paused`; it never deletes the only copy
or resumes before verified offload. An interrupted local transfer retains its
contiguous `.part` file and resumes later. Any size, CRC, fsync, rename, folder,
or identity failure prevents board deletion.

BLE remains unauthenticated for this laboratory prototype.
