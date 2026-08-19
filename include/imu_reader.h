#ifndef IMU_READER_H
#define IMU_READER_H

#include <stdint.h>
#include "app_types.h"

namespace imu_reader {
enum class ReadResult : uint8_t {
  NoData,
  Sample,
  ReadError,
  DeadlineMissed,
};

struct CaptureStats {
  uint32_t rawFrames;
  uint32_t outputSamples;
  uint32_t statusReadRetries;
  uint32_t maxRawIntervalUs;
  uint32_t deadlineMisses;
};

bool begin();
bool startCapture(uint32_t captureStartedAtMs);
void stopCapture();
ReadResult readNext(uint32_t sampleId, IMUSample& sample);
CaptureStats captureStats();
const char* lastError();
uint8_t activeAddress();
}

#endif
