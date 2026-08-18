# Activity Tracker BLE dataset protocol v4

Protocol v4 extends v3 with continuous segmented recording and automatic,
verified offload. Firmware and Android must be upgraded together. The GATT
UUIDs, newline framing, request IDs, MTU-independent fragmentation, binary file
frame format, timeouts, and CRC32 rules remain as defined for v3.

## Version and capabilities

```text
hello,<request_id>
ok,<request_id>,hello,4,recording;catalog;download;resume;crc32;segmentation;auto_offload
```

Client request IDs are `1..4294967295`; `0` remains reserved for asynchronous
firmware faults. Labels remain `walking`, `running`, `cycling`, `sitting`, and
`lying`.

## Segmented recording

- `record_start,<id>,<label>` starts one logical recording.
- Firmware writes an active `.part` file at 50 Hz.
- At 256 KiB, firmware durably syncs and closes the segment, verifies its exact
  size and CRC32, writes the CRC sidecar, renames it to `.csv`, and immediately
  opens a new segment with the same label.
- The logical sample counter and timestamps continue across segment boundaries.
- `record_stop,<id>` finalizes the current segment and ends the logical
  recording.
- BLE disconnect does not stop or rotate the active recording.
- If less than 64 KiB remains after closing a segment, firmware preserves the
  completed segment and enters `Fault` instead of risking an incomplete write.

## Offload while recording

Unlike v3, `status`, `list`, `download`, `cancel`, and guarded `delete` are
allowed while a different `.part` file is actively recording. Only one file
operation may be active at a time. File notifications are rate-limited while
recording so sampling remains the priority.

Android polls status and catalog every two seconds while continuous collection
or an offload backlog exists. For each complete segment it:

1. creates or resumes `<name>.part` in the selected SAF folder;
2. validates contiguous offsets while receiving;
3. flushes, fsyncs, closes, checks the byte count, and calculates CRC32;
4. renames the local partial to the final CSV;
5. opens the final CSV again and rechecks size plus CRC32;
6. sends `delete,<id>,<name>,<size>,<crc32>` automatically;
7. firmware recalculates the current board-file CRC before deleting it.

Any failed local write, fsync, rename, reread, size check, or CRC check prevents
remote deletion. Interrupted transfers retain their valid contiguous partial
and resume later.

## Locked-screen operation

During continuous collection Android runs a `connectedDevice` foreground
service and holds a partial wake lock. This keeps the process, BLE transaction
loop, and file writes active when the screen is locked. The existing reconnect
schedule is 1, 2, 4, 8, and 15 seconds. If Android is unavailable long enough
for QSPI to reach the critical reserve, firmware stops safely in `Fault` rather
than overwriting or deleting unverified data.

BLE remains unauthenticated for this laboratory prototype.
