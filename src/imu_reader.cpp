#include "imu_reader.h"

#include <LSM6DS3.h>
#include <Wire.h>
#include <math.h>

namespace {
constexpr float kRadToDeg = 180.0f / static_cast<float>(PI);

LSM6DS3 imuPrimary(I2C_MODE, 0x6A);
LSM6DS3 imuSecondary(I2C_MODE, 0x6B);
LSM6DS3* activeImu = nullptr;
uint8_t activeImuAddress = 0;

bool tryInitImu(LSM6DS3& candidate, uint8_t address) {
  if (candidate.begin() == 0) {
    activeImu = &candidate;
    activeImuAddress = address;
    return true;
  }

  return false;
}
}

namespace imu_reader {
bool begin() {
  if (tryInitImu(imuPrimary, 0x6A)) {
    return true;
  }

  if (tryInitImu(imuSecondary, 0x6B)) {
    return true;
  }

  return false;
}

IMUSample readSample(uint32_t sampleId, uint32_t timestampMs) {
  const float ax = activeImu->readFloatAccelX();
  const float ay = activeImu->readFloatAccelY();
  const float az = activeImu->readFloatAccelZ();

  IMUSample sample = {
      sampleId,
      timestampMs,
      ax,
      ay,
      az,
      activeImu->readFloatGyroX(),
      activeImu->readFloatGyroY(),
      activeImu->readFloatGyroZ(),
      atan2f(ay, az) * kRadToDeg,
      atan2f(-ax, sqrtf(ay * ay + az * az)) * kRadToDeg,
      activeImu->readTempC(),
      activeImuAddress,
  };

  return sample;
}

uint8_t activeAddress() {
  return activeImuAddress;
}
}
