#ifndef APP_CONFIG_H
#define APP_CONFIG_H

#include <stdint.h>

namespace app_config {
constexpr uint32_t kSerialBaud = 115200;
constexpr uint32_t kSampleIntervalMs = 50;
constexpr uint32_t kFlushEverySamples = 20;
constexpr size_t kCommandBufferSize = 96;
constexpr char kActivityLabel[] = "sitting";
constexpr char kSessionIndexPath[] = "/session_index.txt";
constexpr char kHeaderLine[] =
    "sample_id,timestamp_ms,label,acc_x_g,acc_y_g,acc_z_g,gyro_x_dps,gyro_y_dps,gyro_z_dps,roll_deg,pitch_deg,temp_c,imu_addr\n";
}

#endif
