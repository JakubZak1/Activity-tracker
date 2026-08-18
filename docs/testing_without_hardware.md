# Testing BLE v4 without the physical prototype

This guide defines the software-only verification path for Activity Tracker BLE dataset protocol v4. It covers host-testable firmware logic, Android JVM tests, APK assembly, an Android emulator smoke test, and the in-app segmented-recording simulator.

Passing these checks does **not** verify the nRF52840 radio, QSPI flash, IMU, battery circuit, power-loss behavior, or real BLE throughput. Those remain hardware acceptance tests.

## Verification gates

| Gate | Command or path | Board required |
| --- | --- | --- |
| Main firmware cross-build | `pio run -e seeed_xiao_nrf52840_sense` | No |
| Formatter cross-build | `pio run -e seeed_xiao_nrf52840_sense_formatter` | No |
| Pure v4 firmware modules | `pio test -e native_protocol_tests` | No |
| Android JVM tests and APK | `.\gradlew.bat testDebugUnitTest assembleDebug` | No |
| Android instrumentation smoke | `.\gradlew.bat connectedDebugAndroidTest` on `ActivityTracker_API_35` | No |
| Android v4 simulator workflow | `Settings -> Mock data source -> Connect mock -> Data` | No |
| BLE/QSPI/power acceptance | physical test matrix | Yes |

`native_protocol_tests` and at least one instrumentation smoke test are required v4 integration gates. If the PlatformIO environment or `androidTest` source set is absent, that is missing test coverage, not a passing result. Do not report a command as passed until it exists and exits successfully.

## 1. PowerShell and MSYS2 setup

Run commands from the repository root in a new PowerShell terminal. Native PlatformIO tests use the MSYS2 UCRT64 compiler installed at `C:\msys64\ucrt64\bin`.

Add it to `PATH` for the current terminal only:

```powershell
$msysUcrtBin = 'C:\msys64\ucrt64\bin'
if (-not (Test-Path "$msysUcrtBin\gcc.exe")) {
    throw "MSYS2 UCRT64 GCC was not found at $msysUcrtBin"
}
$env:PATH = "$msysUcrtBin;$env:PATH"

where.exe gcc
gcc --version
g++ --version
pio --version
```

`where.exe gcc` should list the UCRT64 executable first. Keeping this change terminal-local avoids silently changing toolchains used by unrelated projects.

For Android commands, configure the installed SDK and Android Studio runtime if they are not already available:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\android-studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:PATH = "$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:PATH"

java -version
adb version
```

Adjust `JAVA_HOME` only if Android Studio is installed elsewhere.

## 2. Firmware builds and native protocol tests

First verify that both board targets still compile:

```powershell
pio run -e seeed_xiao_nrf52840_sense
pio run -e seeed_xiao_nrf52840_sense_formatter
```

Then run the host-native suite:

```powershell
pio test -e native_protocol_tests
```

The native suite should exercise code that has no Arduino/BLE/flash dependency, including:

- IEEE CRC-32, including the standard `123456789 -> CBF43926` vector and incremental updates;
- v4 command parsing, exact request-ID propagation, label/name/range validation, segmentation boundary, and fragmented newline-delimited control input;
- the recording state machine: idle/start, idempotent same-label start, conflicting start, stop replay, and recording preserved across disconnect;
- contiguous file-frame decisions for accept, full duplicate, gap, overlap, and overflow;
- guarded delete metadata checks.

An unknown `native_protocol_tests` environment, compiler-not-found error, or suite with zero discovered tests means the native gate is incomplete.

## 3. Android JVM tests and debug APK

From the repository root:

```powershell
Push-Location android
.\gradlew.bat testDebugUnitTest assembleDebug --console=plain
Pop-Location
```

The JVM suite should cover at least:

- fragmented v4 control records and request-ID correlation;
- command timeout and reconciliation behavior;
- file-frame duplicate/gap/overlap/overflow handling;
- resume metadata compatibility;
- byte-count and CRC32 success/failure paths;
- repository/controller behavior for start, stop, disconnect, reconnect, continuous segmented offload, CRC gating, and automatic guarded deletion;
- existing payload and calorie calculations.

The debug APK is generated under `android/app/build/outputs/apk/debug/`. A successful compile alone is not a BLE or emulator result.

## 4. Start the `ActivityTracker_API_35` emulator

Confirm that the expected AVD exists:

```powershell
$emulatorExe = "$env:ANDROID_HOME\emulator\emulator.exe"
& $emulatorExe -list-avds
```

The list must contain:

```text
ActivityTracker_API_35
```

Start it in a separate PowerShell window and keep that window open:

```powershell
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd ActivityTracker_API_35 -no-snapshot-load
```

In the original terminal, wait for a complete boot:

```powershell
adb wait-for-device
do {
    $bootCompleted = (adb shell getprop sys.boot_completed).Trim()
    if ($bootCompleted -ne '1') { Start-Sleep -Seconds 2 }
} until ($bootCompleted -eq '1')
```

The Android emulator cannot validate the board's BLE peripheral implementation. Use the app's mock source for this stage.

## 5. Instrumentation smoke gate

Before running the gate, verify that instrumentation tests exist; Gradle must not be allowed to report a misleading `NO-SOURCE` success:

```powershell
Push-Location android
if (-not (Test-Path '.\app\src\androidTest')) {
    throw 'Missing androidTest smoke coverage for BLE v4'
}
.\gradlew.bat connectedDebugAndroidTest --console=plain
Pop-Location
```

At minimum, the smoke test should launch `MainActivity`, navigate with the mock source enabled, and prove that the `Data` workflow can reach its recording and completed-download states without crashing.

## 6. Manual v4 simulator scenario

Install and launch the debug app on the running AVD:

```powershell
Push-Location android
.\gradlew.bat installDebug --console=plain
Pop-Location

adb shell am force-stop pl.edu.activitytracker
adb shell am start -W -n pl.edu.activitytracker/.MainActivity
```

Complete this scenario in the emulator:

1. Open `Settings`, enable `Mock data source`, return to `Home`, and tap `Connect mock`.
2. Open `Data`. Choose or create a folder through the Android system folder picker.
3. Select `walking` and tap `Start`. Confirm that the visible state becomes recording and contains a generated CSV name.
4. Disconnect while recording, reconnect the mock source, and confirm that status reconciliation still reports the active recording.
5. Tap `Stop`. Confirm that the finalized file reports a size and uppercase CRC32.
6. Confirm that download starts automatically, finishes without a `.part` file being presented as complete, and marks the CSV verified.
7. Confirm that deletion is available only for the verified file and still requires the explicit confirmation dialog.
8. Confirm the simulated board copy is removed automatically only after local CRC verification.

If a transfer is long enough to expose `Cancel`, also cancel and resume it. Deterministic gap, overlap, CRC mismatch, and timeout cases belong in automated tests rather than relying on UI timing.

## 7. Result interpretation

Record each command, exit code, test count, and relevant artifact in the development log or pull request. Use these labels consistently:

- **software verified**: board builds, native tests, JVM tests, APK assembly, and emulator smoke all passed;
- **simulator verified**: the manual mock workflow passed on the named AVD;
- **hardware pending**: no claim has been made about physical BLE, QSPI, power, or IMU behavior;
- **hardware verified**: only after the separate physical acceptance matrix passes on the repaired prototype.

Physical acceptance must later include default-MTU operation, real disconnect/reconnect during recording and download, resume after interruption, CRC mismatch rejection, QSPI capacity/failure behavior, power loss during recording/finalization, delete guards, and nearby unauthenticated-client risk.
