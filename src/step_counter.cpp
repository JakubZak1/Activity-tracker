#include "step_counter.h"

#include <limits.h>
#include <math.h>

namespace {
constexpr float kEmaAlpha = 0.35f;
constexpr float kPeakThresholdDps = 80.0f;
constexpr uint32_t kRefractoryMs = 400;
constexpr uint32_t kClassificationLagMs = 2500;
constexpr uint8_t kPendingCapacity = 32;
constexpr double kGyroscopeBias[3] = {
    0.8512324497700314,
    -3.5223251028806586,
    -0.07260530137981118,
};

bool counterEnabled = false;
bool captureActive = false;
float filteredPrevious2 = 0.0f;
float filteredPrevious1 = 0.0f;
uint32_t previousTimestampMs = 0;
uint8_t filteredSamples = 0;
uint32_t lastCandidateMs = 0;
bool hasLastCandidate = false;
uint32_t pendingTimestamps[kPendingCapacity] = {};
uint8_t pendingHead = 0;
uint8_t pendingCount = 0;
uint32_t total = 0;
uint32_t dropped = 0;
bool hasClassification = false;
bool lastStepActivity = false;

float quantizeCsvValue(float value) {
  return roundf(value * 10000.0f) / 10000.0f;
}

void resetCaptureState() {
  filteredPrevious2 = 0.0f;
  filteredPrevious1 = 0.0f;
  previousTimestampMs = 0;
  filteredSamples = 0;
  lastCandidateMs = 0;
  hasLastCandidate = false;
  pendingHead = 0;
  pendingCount = 0;
  hasClassification = false;
  lastStepActivity = false;
}

void enqueueCandidate(uint32_t timestampMs) {
  if (pendingCount >= kPendingCapacity) {
    ++dropped;
    return;
  }
  const uint8_t tail = static_cast<uint8_t>((pendingHead + pendingCount) % kPendingCapacity);
  pendingTimestamps[tail] = timestampMs;
  ++pendingCount;
}

void incrementTotal() {
  if (total != UINT32_MAX) ++total;
}

uint8_t drainThrough(uint32_t cutoffMs, bool countAsSteps) {
  uint8_t committed = 0;
  while (pendingCount > 0 &&
         static_cast<int32_t>(pendingTimestamps[pendingHead] - cutoffMs) <= 0) {
    if (countAsSteps) {
      incrementTotal();
      if (committed != UINT8_MAX) ++committed;
    }
    pendingHead = static_cast<uint8_t>((pendingHead + 1U) % kPendingCapacity);
    --pendingCount;
  }
  return committed;
}

uint8_t drainAll(bool countAsSteps) {
  uint8_t committed = 0;
  while (pendingCount > 0) {
    if (countAsSteps) {
      incrementTotal();
      if (committed != UINT8_MAX) ++committed;
    }
    pendingHead = static_cast<uint8_t>((pendingHead + 1U) % kPendingCapacity);
    --pendingCount;
  }
  return committed;
}
}

namespace step_counter {
void begin(bool enabledValue) {
  counterEnabled = enabledValue;
  captureActive = false;
  total = 0;
  dropped = 0;
  resetCaptureState();
}

bool enabled() {
  return counterEnabled;
}

void beginCapture() {
  if (!counterEnabled) return;
  if (captureActive) endCapture();
  resetCaptureState();
  captureActive = true;
}

void push(const IMUSample& sample) {
  if (!counterEnabled || !captureActive) return;
  const double gx = static_cast<double>(quantizeCsvValue(sample.gyroX)) - kGyroscopeBias[0];
  const double gy = static_cast<double>(quantizeCsvValue(sample.gyroY)) - kGyroscopeBias[1];
  const double gz = static_cast<double>(quantizeCsvValue(sample.gyroZ)) - kGyroscopeBias[2];
  const float magnitude = static_cast<float>(sqrt((gx * gx) + (gy * gy) + (gz * gz)));

  if (filteredSamples == 0) {
    filteredPrevious1 = magnitude;
    previousTimestampMs = sample.timestampMs;
    filteredSamples = 1;
    return;
  }
  const float current = filteredPrevious1 + kEmaAlpha * (magnitude - filteredPrevious1);
  if (filteredSamples >= 2 &&
      filteredPrevious2 < filteredPrevious1 &&
      filteredPrevious1 >= current &&
      filteredPrevious1 >= kPeakThresholdDps &&
      (!hasLastCandidate ||
       static_cast<uint32_t>(previousTimestampMs - lastCandidateMs) >= kRefractoryMs)) {
    enqueueCandidate(previousTimestampMs);
    lastCandidateMs = previousTimestampMs;
    hasLastCandidate = true;
  }
  filteredPrevious2 = filteredPrevious1;
  filteredPrevious1 = current;
  previousTimestampMs = sample.timestampMs;
  if (filteredSamples < 2) ++filteredSamples;
}

uint8_t classifyWindow(uint32_t windowEndMs, bool stepActivity) {
  if (!counterEnabled || !captureActive) return 0;
  hasClassification = true;
  lastStepActivity = stepActivity;
  const uint32_t cutoffMs = windowEndMs >= kClassificationLagMs
                                ? windowEndMs - kClassificationLagMs
                                : 0;
  return drainThrough(cutoffMs, stepActivity);
}

uint8_t endCapture() {
  if (!counterEnabled || !captureActive) return 0;
  const uint8_t committed = drainAll(hasClassification && lastStepActivity);
  captureActive = false;
  resetCaptureState();
  return committed;
}

uint32_t totalSteps() {
  return total;
}

#ifdef STEP_COUNTER_TESTING
uint8_t pendingCandidates() {
  return pendingCount;
}

uint32_t droppedCandidates() {
  return dropped;
}
#endif
}
