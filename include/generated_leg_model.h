#ifndef GENERATED_LEG_MODEL_H
#define GENERATED_LEG_MODEL_H

#include <stdint.h>

namespace generated_leg_model {
constexpr uint16_t kTreeCount = 20;
constexpr uint16_t kNodeCount = 1312;
constexpr uint8_t kFeatureCount = 27;
constexpr uint8_t kClassCount = 5;
constexpr uint8_t kLeafFeature = 255;
constexpr uint16_t kNoChild = 65535;

extern const uint16_t kTreeOffsets[kTreeCount + 1];
extern const uint8_t kFeatures[kNodeCount];
extern const uint16_t kLeftChildren[kNodeCount];
extern const uint16_t kRightChildren[kNodeCount];
extern const float kThresholds[kNodeCount];
extern const float kLeafProbabilities[kNodeCount * kClassCount];
extern const char* const kClassLabels[kClassCount];
}

#endif
