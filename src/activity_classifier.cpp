#include "activity_classifier.h"

#include <math.h>
#include <string.h>

namespace {
constexpr uint16_t kWindowSamples = 260;
constexpr uint16_t kStrideSamples = 130;
constexpr uint8_t kRawSignalCount = 6;
constexpr uint8_t kMagnitudeSignalCount = 3;
constexpr float kSampleRateHz = 52.0f;
constexpr float kTwoPi = 6.28318530717958647692f;
constexpr char kLegDeviceShortId[] = "18EE26A8";

// xiao_unit_02 / 18EE26A8 six-position calibration. Keep the correction
// order identical to tools/ml_dataset.py and calibration/xiao_unit_02.json.
constexpr double kAccelerationOffset[3] = {
    -0.009282170281277424,
    0.009874050463454964,
    0.03166534711881919,
};
constexpr double kAccelerationScale[3] = {
    0.9999783930510902,
    0.9919650238667072,
    1.0018282711484991,
};
constexpr double kGyroscopeBias[3] = {
    0.8512324497700314,
    -3.5223251028806586,
    -0.07260530137981118,
};

struct SensorVector {
  float values[kRawSignalCount];
};

SensorVector window[kWindowSamples] = {};
float magnitudeWindow[kMagnitudeSignalCount][kWindowSamples] = {};
uint16_t writeIndex = 0;
uint16_t sampleCount = 0;
uint16_t samplesSincePrediction = 0;
bool classifierEnabled = false;

float quantizeCsvValue(float value) {
  // Training uses the four-decimal CSV representation emitted by data_logger.
  return roundf(value * 10000.0f) / 10000.0f;
}

SensorVector calibrate(const IMUSample& sample) {
  const float raw[kRawSignalCount] = {
      sample.accX,
      sample.accY,
      sample.accZ,
      sample.gyroX,
      sample.gyroY,
      sample.gyroZ,
  };
  SensorVector corrected = {};
  for (uint8_t axis = 0; axis < 3; ++axis) {
    const double quantized = static_cast<double>(quantizeCsvValue(raw[axis]));
    corrected.values[axis] = static_cast<float>(
        (quantized - kAccelerationOffset[axis]) * kAccelerationScale[axis]);
  }
  for (uint8_t axis = 0; axis < 3; ++axis) {
    const double quantized = static_cast<double>(quantizeCsvValue(raw[axis + 3]));
    corrected.values[axis + 3] = static_cast<float>(quantized - kGyroscopeBias[axis]);
  }
  return corrected;
}

float dominantFrequency(const float* values, float mean) {
  float bestPower = -1.0f;
  uint16_t bestBin = 1;
  const uint16_t lastBin = kWindowSamples / 2;
  for (uint16_t bin = 1; bin <= lastBin; ++bin) {
    float power = 0.0f;
    if (bin == lastBin) {
      float alternating = 0.0f;
      for (uint16_t index = 0; index < kWindowSamples; ++index) {
        const float centered = values[index] - mean;
        alternating += (index & 1U) == 0 ? centered : -centered;
      }
      power = alternating * alternating;
    } else {
      const float coefficient =
          2.0f * cosf(kTwoPi * static_cast<float>(bin) / static_cast<float>(kWindowSamples));
      float previous = 0.0f;
      float beforePrevious = 0.0f;
      for (uint16_t index = 0; index < kWindowSamples; ++index) {
        const float current =
            (values[index] - mean) + coefficient * previous - beforePrevious;
        beforePrevious = previous;
        previous = current;
      }
      power = previous * previous + beforePrevious * beforePrevious -
              coefficient * previous * beforePrevious;
    }
    if (power > bestPower) {
      bestPower = power;
      bestBin = bin;
    }
  }
  return static_cast<float>(bestBin) * kSampleRateHz / static_cast<float>(kWindowSamples);
}

void extractFeatures(float features[generated_leg_model::kFeatureCount]) {
  double rawSums[kRawSignalCount] = {};
  double rawMeans[kRawSignalCount] = {};
  double rawSquaredDeviations[kRawSignalCount] = {};
  double magnitudeSums[kMagnitudeSignalCount] = {};
  double magnitudeMeans[kMagnitudeSignalCount] = {};
  double magnitudeSquaredDeviations[kMagnitudeSignalCount] = {};
  double magnitudeMinimums[kMagnitudeSignalCount] = {};
  double magnitudeMaximums[kMagnitudeSignalCount] = {};
  bool first = true;

  for (uint16_t sampleIndex = 0; sampleIndex < kWindowSamples; ++sampleIndex) {
    const SensorVector& sample = window[sampleIndex];
    for (uint8_t signal = 0; signal < kRawSignalCount; ++signal) {
      rawSums[signal] += sample.values[signal];
    }
    const double ax = sample.values[0];
    const double ay = sample.values[1];
    const double az = sample.values[2];
    const double gx = sample.values[3];
    const double gy = sample.values[4];
    const double gz = sample.values[5];
    const float accelerationMagnitude = static_cast<float>(sqrt((ax * ax) + (ay * ay) + (az * az)));
    const float magnitudes[kMagnitudeSignalCount] = {
        accelerationMagnitude,
        fabsf(accelerationMagnitude - 1.0f),
        static_cast<float>(sqrt((gx * gx) + (gy * gy) + (gz * gz))),
    };
    for (uint8_t signal = 0; signal < kMagnitudeSignalCount; ++signal) {
      magnitudeWindow[signal][sampleIndex] = magnitudes[signal];
      magnitudeSums[signal] += magnitudes[signal];
      if (first || magnitudes[signal] < magnitudeMinimums[signal]) {
        magnitudeMinimums[signal] = magnitudes[signal];
      }
      if (first || magnitudes[signal] > magnitudeMaximums[signal]) {
        magnitudeMaximums[signal] = magnitudes[signal];
      }
    }
    first = false;
  }
  for (uint8_t signal = 0; signal < kRawSignalCount; ++signal) {
    rawMeans[signal] = rawSums[signal] / static_cast<double>(kWindowSamples);
  }
  for (uint8_t signal = 0; signal < kMagnitudeSignalCount; ++signal) {
    magnitudeMeans[signal] = magnitudeSums[signal] / static_cast<double>(kWindowSamples);
  }
  for (uint16_t sampleIndex = 0; sampleIndex < kWindowSamples; ++sampleIndex) {
    const SensorVector& sample = window[sampleIndex];
    for (uint8_t signal = 0; signal < kRawSignalCount; ++signal) {
      const double difference = sample.values[signal] - rawMeans[signal];
      rawSquaredDeviations[signal] += difference * difference;
    }
    for (uint8_t signal = 0; signal < kMagnitudeSignalCount; ++signal) {
      const double difference = magnitudeWindow[signal][sampleIndex] - magnitudeMeans[signal];
      magnitudeSquaredDeviations[signal] += difference * difference;
    }
  }

  uint8_t output = 0;
  for (uint8_t signal = 0; signal < kRawSignalCount; ++signal) {
    features[output++] = static_cast<float>(rawMeans[signal]);
    features[output++] = static_cast<float>(
        sqrt(rawSquaredDeviations[signal] / static_cast<double>(kWindowSamples)));
  }
  for (uint8_t signal = 0; signal < kMagnitudeSignalCount; ++signal) {
    features[output++] = static_cast<float>(magnitudeMeans[signal]);
    features[output++] = static_cast<float>(
        sqrt(magnitudeSquaredDeviations[signal] / static_cast<double>(kWindowSamples)));
    features[output++] = static_cast<float>(magnitudeMinimums[signal]);
    features[output++] = static_cast<float>(magnitudeMaximums[signal]);
    features[output++] = dominantFrequency(
        magnitudeWindow[signal], static_cast<float>(magnitudeMeans[signal]));
  }
}
}

namespace activity_classifier {
bool begin(const char* deviceShortId) {
  classifierEnabled = deviceShortId != nullptr && strcmp(deviceShortId, kLegDeviceShortId) == 0;
  reset();
  return classifierEnabled;
}

bool enabled() {
  return classifierEnabled;
}

void reset() {
  writeIndex = 0;
  sampleCount = 0;
  samplesSincePrediction = 0;
}

Prediction predictFeatures(const float features[generated_leg_model::kFeatureCount]) {
  float probabilities[generated_leg_model::kClassCount] = {};
  for (uint16_t tree = 0; tree < generated_leg_model::kTreeCount; ++tree) {
    uint16_t node = generated_leg_model::kTreeOffsets[tree];
    while (generated_leg_model::kFeatures[node] != generated_leg_model::kLeafFeature) {
      const uint8_t feature = generated_leg_model::kFeatures[node];
      node = features[feature] <= generated_leg_model::kThresholds[node]
                 ? generated_leg_model::kLeftChildren[node]
                 : generated_leg_model::kRightChildren[node];
    }
    const uint32_t base = static_cast<uint32_t>(node) * generated_leg_model::kClassCount;
    for (uint8_t classIndex = 0; classIndex < generated_leg_model::kClassCount; ++classIndex) {
      probabilities[classIndex] += generated_leg_model::kLeafProbabilities[base + classIndex];
    }
  }

  uint8_t bestClass = 0;
  for (uint8_t classIndex = 1; classIndex < generated_leg_model::kClassCount; ++classIndex) {
    if (probabilities[classIndex] > probabilities[bestClass]) bestClass = classIndex;
  }
  float confidence = probabilities[bestClass] * 100.0f / generated_leg_model::kTreeCount;
  if (confidence < 0.0f) confidence = 0.0f;
  if (confidence > 100.0f) confidence = 100.0f;
  return {bestClass, static_cast<uint8_t>(confidence + 0.5f)};
}

bool push(const IMUSample& sample, Prediction& prediction) {
  if (!classifierEnabled) return false;
  window[writeIndex] = calibrate(sample);
  writeIndex = static_cast<uint16_t>((writeIndex + 1U) % kWindowSamples);
  if (sampleCount < kWindowSamples) ++sampleCount;
  ++samplesSincePrediction;
  if (sampleCount < kWindowSamples) return false;
  if (samplesSincePrediction < kStrideSamples && sampleCount == kWindowSamples) return false;
  // The first full window predicts immediately. Later predictions use the
  // configured 130-sample (2.5 s) stride.
  samplesSincePrediction = 0;
  float features[generated_leg_model::kFeatureCount] = {};
  extractFeatures(features);
  prediction = predictFeatures(features);
  return true;
}

const char* label(uint8_t classIndex) {
  return classIndex < generated_leg_model::kClassCount
             ? generated_leg_model::kClassLabels[classIndex]
             : "unknown";
}

#ifdef ACTIVITY_CLASSIFIER_TESTING
bool extractFeaturesForSamples(
    const IMUSample* samples,
    uint16_t count,
    float features[generated_leg_model::kFeatureCount]) {
  if (samples == nullptr || features == nullptr || count != kWindowSamples) return false;
  for (uint16_t index = 0; index < kWindowSamples; ++index) {
    window[index] = calibrate(samples[index]);
  }
  extractFeatures(features);
  return true;
}
#endif
}
