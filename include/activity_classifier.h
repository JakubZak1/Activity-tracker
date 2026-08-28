#ifndef ACTIVITY_CLASSIFIER_H
#define ACTIVITY_CLASSIFIER_H

#include <stdint.h>

#include "app_types.h"
#include "generated_leg_model.h"

namespace activity_classifier {
struct Prediction {
  uint8_t classIndex;
  uint8_t confidencePercent;
};

bool begin(const char* deviceShortId);
bool enabled();
void reset();
bool push(const IMUSample& sample, Prediction& prediction);
Prediction predictFeatures(const float features[generated_leg_model::kFeatureCount]);
const char* label(uint8_t classIndex);
#ifdef ACTIVITY_CLASSIFIER_TESTING
bool extractFeaturesForSamples(
    const IMUSample* samples,
    uint16_t count,
    float features[generated_leg_model::kFeatureCount]);
#endif
}

#endif
