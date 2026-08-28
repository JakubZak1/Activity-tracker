# Activity Tracker BLE dataset protocol v6

Protocol v6 extends the loss-safe segmented recording protocol v5 with stable
device identity and explicit visual identification. Firmware and Android must
be upgraded together; the GATT UUIDs, request IDs, newline framing,
MTU-independent fragmentation, CRC32 verification and pause/offload behavior
remain unchanged.

## Identity and handshake

Each board derives a stable 64-bit uppercase hexadecimal ID from the nRF52840
FICR device identifier. It advertises as `ActivityTracker-XXXXXXXX`, where the
suffix is the final eight characters of the full ID.

```text
hello,<request_id>
ok,<request_id>,hello,6,<full_id>,<short_id>,recording;catalog;download;resume;crc32;segmentation;auto_offload;pause_offload;imu_drdy104_mean2_52_deadline_guard;stable_device_id;rgb_identify;unique_filenames
```

Client request IDs are `1..4294967295`; `0` is reserved for asynchronous
firmware events. Android treats the full ID returned by `hello` as the source
of truth. A BLE address is only a transport address and may change.

## Visual identification

```text
identify,<request_id>,<blue|green>,<duration_ms>
ok,<request_id>,identified,<blue|green>,<duration_ms>
```

`duration_ms` is between 500 and 10000. Identification is non-blocking and is
allowed while recording. Blue and green distinguish the two collection slots;
red remains reserved for faults.

## Recording and filenames

The v5 recording, status, list, download, cancel and guarded-delete commands
are retained. Labels remain `walking`, `running`, `cycling`, `sitting`, and
`lying`.

New finalized and partial files use:

```text
<short_id_lowercase>_<label>_<index>.csv
<short_id_lowercase>_<label>_<index>.part
```

For example, `a1b2c3d4_walking_17.csv`. This prevents collisions when two
boards save into the same Android document tree. Android additionally stores
each device under `xiao_<short_id_lowercase>/` and records the full hardware
ID, placement, body side, local session ID and shared paired-session ID in the
session sidecar.

## Two-device collection

Android owns two independent GATT clients and protocol controllers. A shared
Start creates one paired-session UUID and sends one atomic `record_start` to
each board. If only one board starts, Android stops that board and reports a
paired-start failure. Stop is sent to both boards. Recording can occur
concurrently, while bulk downloads are serialized to reduce BLE and document
provider pressure.

The boards are not sample-clock synchronized. The paired-session ID provides
experimental correspondence, while each CSV retains its own timestamps. This
is sufficient for training and comparing separate wrist and leg models; it is
not intended for sample-level sensor fusion.

## Locked screen and recovery

The connected-device foreground service and partial wake lock remain active
while either board records or transfers. Each slot reconnects independently
after 1, 2, 4, 8 and 15 seconds. On reconnect, `hello` and authoritative
`status` reconcile the logger. A transfer is saved to `.part`, resumed from
the last contiguous byte and finalized only after size and CRC32 match.

BLE remains unauthenticated for this laboratory prototype.
