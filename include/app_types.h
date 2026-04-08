#ifndef APP_TYPES_H
#define APP_TYPES_H

#include <stdint.h>

struct IMUSample {
  uint32_t sampleId;
  uint32_t timestampMs;
  float accX;
  float accY;
  float accZ;
  float gyroX;
  float gyroY;
  float gyroZ;
  float rollDeg;
  float pitchDeg;
  float tempC;
  uint8_t imuAddress;
};

#endif
