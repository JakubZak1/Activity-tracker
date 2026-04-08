#ifndef IMU_READER_H
#define IMU_READER_H

#include <stdint.h>
#include "app_types.h"

namespace imu_reader {
bool begin();
IMUSample readSample(uint32_t sampleId, uint32_t timestampMs);
uint8_t activeAddress();
}

#endif
