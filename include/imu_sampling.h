#ifndef IMU_SAMPLING_H
#define IMU_SAMPLING_H

#include <stddef.h>
#include <stdint.h>

namespace imu_sampling {
constexpr uint32_t kInputRateHz = 104;
constexpr uint32_t kOutputRateHz = 52;
constexpr uint8_t kWordsPerFrame = 6;
constexpr uint8_t kFramesPerOutputSample = 2;
constexpr uint8_t kWordsPerOutputSample = kWordsPerFrame * kFramesPerOutputSample;

struct RawFrame {
  int16_t gyroX;
  int16_t gyroY;
  int16_t gyroZ;
  int16_t accX;
  int16_t accY;
  int16_t accZ;
};

struct AveragedFrame {
  float gyroX;
  float gyroY;
  float gyroZ;
  float accX;
  float accY;
  float accZ;
};

class FifoFrameAssembler {
 public:
  void reset();
  bool push(uint16_t pattern, int16_t word, RawFrame& completed);
  uint32_t discardedWords() const;
  uint8_t expectedPattern() const;

 private:
  RawFrame partial_ = {};
  uint8_t expectedPattern_ = 0;
  bool synchronized_ = false;
  uint32_t discardedWords_ = 0;
};

class PairAverager {
 public:
  void reset();
  bool push(const RawFrame& input, AveragedFrame& output);

 private:
  RawFrame first_ = {};
  bool hasFirst_ = false;
};

class TimestampSequence {
 public:
  void reset(uint32_t captureStartedAtMs);
  uint32_t next();

 private:
  uint64_t nextNumerator_ = 0;
};
}

#endif
