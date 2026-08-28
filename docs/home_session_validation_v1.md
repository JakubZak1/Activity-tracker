# Home session physical validation v1

Date: 2026-08-23  
Phone: A063, Android 15  
Device: Green `18EE26A8`, left lower leg  
Application: Activity Tracker 1.0.0

## Procedure

1. Connected the configured Green device over BLE and waited for `Ready`.
2. Opened Home and waited for the `Sitting` prediction.
3. Started a Home session while sitting.
4. Kept the phone unlocked for approximately 20 seconds.
5. Locked the phone for approximately 60 seconds without intentionally
   disconnecting BLE.
6. Unlocked the phone, confirmed that the session was still active, and used
   Stop.
7. Opened the stored History detail and checked the export state.

## Observed result

| Field | Value |
|---|---:|
| Session status | Completed |
| Total duration | 01:27 |
| Sitting | 01:27 |
| Unknown | 00:00 |
| Steps | 0 |
| Estimated calories | 2.31 kcal |
| Export state | Exported |
| Summary file | `home_20260823_221926_0b6a66f6.json` |
| Route file | `home_20260823_221926_0b6a66f6_route.csv` |

`Unknown` was not rendered as a separate non-zero item in the user interface;
because sitting duration exactly equalled total duration, its derived value was
zero. The result is consistent with uninterrupted, fresh Green telemetry for
the complete measured interval.

An ADB read-only check confirmed that both files existed in
`activity tracker dane/home_sessions`: the JSON was 600 bytes and the route CSV
was 209 bytes with four lines (header plus three GPS points).

## Acceptance decision

Passed. The physical test covers the final Home path across a locked-screen
interval: Green selection and BLE telemetry, activity-duration aggregation,
step and calorie presentation, final Stop, durable History storage, and
automatic SAF JSON export.

This short sitting test is an acceptance smoke test, not a long-term battery or
BLE stress certificate. Existing limitations listed in
`PROJECT_COMPLETION.md` remain unchanged.
