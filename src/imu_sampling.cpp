#include "imu_sampling.h"

namespace imu_sampling {
void FifoFrameAssembler::reset() {
  partial_ = {};
  expectedPattern_ = 0;
  synchronized_ = false;
  discardedWords_ = 0;
}

bool FifoFrameAssembler::push(uint16_t pattern, int16_t word, RawFrame& completed) {
  if (pattern >= kWordsPerFrame) {
    ++discardedWords_;
    synchronized_ = false;
    expectedPattern_ = 0;
    return false;
  }

  if (!synchronized_) {
    if (pattern != 0) {
      ++discardedWords_;
      return false;
    }
    synchronized_ = true;
    expectedPattern_ = 0;
  }

  if (pattern != expectedPattern_) {
    ++discardedWords_;
    synchronized_ = false;
    expectedPattern_ = 0;
    if (pattern != 0) {
      return false;
    }
    synchronized_ = true;
  }

  switch (pattern) {
    case 0:
      partial_.gyroX = word;
      break;
    case 1:
      partial_.gyroY = word;
      break;
    case 2:
      partial_.gyroZ = word;
      break;
    case 3:
      partial_.accX = word;
      break;
    case 4:
      partial_.accY = word;
      break;
    case 5:
      partial_.accZ = word;
      break;
    default:
      return false;
  }

  expectedPattern_ = static_cast<uint8_t>((pattern + 1U) % kWordsPerFrame);
  if (pattern != kWordsPerFrame - 1U) {
    return false;
  }
  completed = partial_;
  return true;
}

uint32_t FifoFrameAssembler::discardedWords() const {
  return discardedWords_;
}

uint8_t FifoFrameAssembler::expectedPattern() const {
  return synchronized_ ? expectedPattern_ : 0;
}

void PairAverager::reset() {
  first_ = {};
  hasFirst_ = false;
}

bool PairAverager::push(const RawFrame& input, AveragedFrame& output) {
  if (!hasFirst_) {
    first_ = input;
    hasFirst_ = true;
    return false;
  }

  output.gyroX = (static_cast<float>(first_.gyroX) + input.gyroX) * 0.5f;
  output.gyroY = (static_cast<float>(first_.gyroY) + input.gyroY) * 0.5f;
  output.gyroZ = (static_cast<float>(first_.gyroZ) + input.gyroZ) * 0.5f;
  output.accX = (static_cast<float>(first_.accX) + input.accX) * 0.5f;
  output.accY = (static_cast<float>(first_.accY) + input.accY) * 0.5f;
  output.accZ = (static_cast<float>(first_.accZ) + input.accZ) * 0.5f;
  hasFirst_ = false;
  return true;
}

void TimestampSequence::reset(uint32_t captureStartedAtMs) {
  nextNumerator_ = static_cast<uint64_t>(captureStartedAtMs) * kOutputRateHz + 1000ULL;
}

uint32_t TimestampSequence::next() {
  const uint32_t result = static_cast<uint32_t>(nextNumerator_ / kOutputRateHz);
  nextNumerator_ += 1000ULL;
  return result;
}
}
