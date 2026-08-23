# Lower-leg step counter

## Scope

The current counter is enabled only on Green `18EE26A8`, mounted on the left
lower leg. Firmware exposes a monotonically increasing total since device boot.
Android stores the device total at the start of a `Home` session and displays
only the subsequent delta. A lower device total during a session is treated as
a board reboot and counting continues from the new value.

The counter is an engineering candidate until it is compared with manually
counted reference steps. Existing activity recordings do not contain reference
step totals and therefore cannot establish counting accuracy.

## Algorithm

At 52 Hz, firmware:

1. quantizes each gyroscope axis to the same four-decimal representation used
   by CSV and removes the Green calibration bias;
2. calculates gyroscope magnitude;
3. applies a one-pole EMA with `alpha = 0.35`;
4. detects a local maximum of at least `80 dps`;
5. accepts at most one peak every `400 ms`.

For the lower-leg mounting used here, gyroscope magnitude contains two
swing-related peaks per complete cycle of the instrumented leg. Each accepted
peak is therefore treated as one whole-body step; it is not multiplied by two.

Cycling also produces strong periodic peaks, so peak detection alone is not
sufficient. Candidate timestamps are kept in a fixed 32-entry buffer. Every
2.5 seconds, the five-second embedded classifier window decides candidates up
to 2.5 seconds in the past. Candidates are committed only if the raw window
class is `walking` or `running`; other candidates are discarded. This delayed
decision retains the beginning of walking/running instead of losing the first
five seconds while also suppressing cycling.

## Data preflight without ground truth

The exact firmware flow was reproduced over the nine fresh embedded-v2 files:

| Session | Candidate peaks | Counted steps | Estimated cadence |
|---|---:|---:|---:|
| cycling | 131 | 0 | 0.0 steps/min |
| lying | 0 | 0 | 0.0 steps/min |
| sitting | 0 | 0 | 0.0 steps/min |
| slow walking | 76 | 76 | 70.7 steps/min |
| normal walking | 104 | 104 | 97.9 steps/min |
| left circles | 89 | 89 | 84.6 steps/min |
| right circles | 87 | 87 | 86.0 steps/min |
| slow jogging | 135 | 114 | 108.1 steps/min |
| normal running | 145 | 145 | 134.1 steps/min |

These rates are plausible but are not accuracy measurements. Slow jogging is
undercounted relative to its 135 candidate peaks because four low-confidence
classifier windows were labeled `cycling`. This dependency on classifier errors
must remain explicit.

Native tests cover stationary rejection, delayed commitment, cycling gating,
and persistence across capture restarts. The complete native suite passes
19/19 cases. Firmware with the counter uses 27,776/237,568 bytes RAM (11.7%)
and 226,676/811,008 bytes flash (27.9%). Android unit tests and `assembleDebug`
also pass.

Generated preflight evidence is stored at
`dataset/results/step_counter_candidate/preflight.json`.

## Required physical validation

Use the standard Green lower-leg mount and the `Home` connection. For every
trial, tap `Start`, perform an independently counted number of steps, then stand
still for at least five seconds before reading the result and tapping `Stop`.
The pause allows the delayed classifier gate to commit the final candidates.

Minimum matrix:

- three trials of exactly 100 normal walking steps;
- three trials of exactly 100 deliberately slow walking steps;
- two trials of exactly 100 comfortable jogging steps if safe;
- two minutes sitting and two minutes cycling, both expected to add zero steps.

Record reference steps, displayed steps, signed error, absolute error, and
percentage error for every trial. Do not tune thresholds between trials. Any
later tuning turns this matrix into development data and requires a new final
repeat.

## Physical results

Thresholds remained frozen after the preflight.

| Trial | Activity and conditions | Reference | Displayed | Signed error | Absolute percentage error |
|---:|---|---:|---:|---:|---:|
| 1 | normal corridor walking with tight natural turns | 100 | 96 | -4 | 4.0% |
| 2 | normal walking | 100 | 99 | -1 | 1.0% |
| 3 | normal walking | 100 | 95 | -5 | 5.0% |
| 4 | deliberately slow walking | 100 | 112 | +12 | 12.0% |
| 5 | deliberately slow walking | 100 | 110 | +10 | 10.0% |
| 6 | deliberately slow walking | 100 | 105 | +5 | 5.0% |
| 7 | comfortable running/jogging | 100 | 88 | -12 | 12.0% |
| 8 | comfortable running/jogging | 100 | 90 | -10 | 10.0% |
| 9 | sitting, 3 minutes | 0 | 0 | 0 | not applicable |
| 10 | lying, 3 minutes | 0 | 0 | 0 | not applicable |
| 11 | cycling, 8 minutes | 0 | 0 | 0 | not applicable |

For the eight movement trials, the counter reported 795 of 800 reference
steps in total. This aggregate error of -0.625% is not representative on its
own because errors in different gait regimes cancel. The mean absolute error
per 100-step trial was 7.375 steps. Broken down by regime, normal walking had
a mean signed error of -3.33 steps and a mean absolute percentage error of
3.33%; slow walking over-counted by 9.0% on average; running under-counted by
11.0% on average. No false steps were observed during 14 combined minutes of
sitting, lying, and cycling.

The opposite signed errors for slow walking and running mean that a single
global correction factor would be inappropriate. These frozen-threshold
results are retained as the physical validation of the baseline algorithm.
