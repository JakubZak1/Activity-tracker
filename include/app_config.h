#ifndef APP_CONFIG_H
#define APP_CONFIG_H

#include <stdint.h>

namespace app_config {
constexpr uint32_t kSerialBaud = 115200;
constexpr uint32_t kSampleIntervalMs = 20;
constexpr uint32_t kFlushEverySamples = 20;
constexpr uint32_t kLedPulseEverySamples = 10;
constexpr uint32_t kLedPulseDurationMs = 5;
constexpr uint32_t kBleTelemetryIntervalMs = 1000;
constexpr uint32_t kBleBatteryIntervalMs = 30000;
constexpr size_t kCommandBufferSize = 256;
constexpr size_t kActivityLabelBufferSize = 24;
constexpr size_t kBlePayloadBufferSize = 48;
constexpr size_t kBleCommandBufferSize = 256;
constexpr size_t kBleControlResponseBufferSize = 256;
constexpr size_t kBleFileFrameSize = 244;
constexpr uint32_t kBleControlTimeoutMs = 5000;
constexpr uint32_t kBleFileOperationTimeoutMs = 10000;
constexpr uint32_t kSegmentMaxBytes = 1536UL * 1024UL;
constexpr uint32_t kCriticalFreeBytes = 64UL * 1024UL;
constexpr uint32_t kBleTransferFrameIntervalWhileRecordingMs = 5;
constexpr char kDefaultActivityLabel[] = "sitting";
constexpr char kBleDeviceName[] = "ActivityTracker";
constexpr char kSessionIndexPath[] = "/session_index.txt";
constexpr char kHeaderLine[] =
    "timestamp_ms,label,acc_x_g,acc_y_g,acc_z_g,gyro_x_dps,gyro_y_dps,gyro_z_dps\n";
}

#endif
