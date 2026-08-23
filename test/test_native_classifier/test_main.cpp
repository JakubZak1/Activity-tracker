#include <unity.h>
#include <math.h>

#include "activity_classifier.h"
#include "generated_golden.h"
#include "generated_raw_golden.h"
#include "step_counter.h"

namespace {
constexpr float kPi = 3.14159265358979323846f;

uint32_t sampleTimestamp(uint16_t index) {
  return static_cast<uint32_t>(lroundf(static_cast<float>(index) * 1000.0f / 52.0f));
}

void pushSyntheticGait(uint16_t sampleCount, float strideHz, bool stepActivity) {
  for (uint16_t index = 0; index < sampleCount; ++index) {
    IMUSample sample = {};
    sample.timestampMs = sampleTimestamp(index);
    const float seconds = static_cast<float>(sample.timestampMs) / 1000.0f;
    sample.gyroZ = 240.0f * sinf(2.0f * kPi * strideHz * seconds);
    step_counter::push(sample);
    if (index == 259 || (index > 259 && (index - 259) % 130 == 0)) {
      step_counter::classifyWindow(sample.timestampMs, stepActivity);
    }
  }
}
}

void setUp() {}
void tearDown() {}

void testGeneratedForestMatchesSklearnGoldenWindows() {
  for (uint8_t index = 0; index < embedded_model_golden::kCount; ++index) {
    const activity_classifier::Prediction prediction =
        activity_classifier::predictFeatures(embedded_model_golden::kFeatures[index]);
    TEST_ASSERT_EQUAL_UINT8(embedded_model_golden::kExpected[index], prediction.classIndex);
    TEST_ASSERT_GREATER_OR_EQUAL_UINT8(1, prediction.confidencePercent);
    TEST_ASSERT_LESS_OR_EQUAL_UINT8(100, prediction.confidencePercent);
  }
}

void testClassifierIsEnabledOnlyForCalibratedGreenDevice() {
  TEST_ASSERT_FALSE(activity_classifier::begin("872F1832"));
  TEST_ASSERT_FALSE(activity_classifier::enabled());
  TEST_ASSERT_TRUE(activity_classifier::begin("18EE26A8"));
  TEST_ASSERT_TRUE(activity_classifier::enabled());
}

void testWindowAndStrideSchedule() {
  TEST_ASSERT_TRUE(activity_classifier::begin("18EE26A8"));
  IMUSample sample = {};
  sample.accZ = 1.0f;
  activity_classifier::Prediction prediction = {};
  for (uint16_t index = 0; index < 259; ++index) {
    TEST_ASSERT_FALSE(activity_classifier::push(sample, prediction));
  }
  TEST_ASSERT_TRUE(activity_classifier::push(sample, prediction));
  for (uint16_t index = 0; index < 129; ++index) {
    TEST_ASSERT_FALSE(activity_classifier::push(sample, prediction));
  }
  TEST_ASSERT_TRUE(activity_classifier::push(sample, prediction));
}

void testRawCsvWindowsMatchPythonFeaturesAndPredictions() {
  for (uint8_t windowIndex = 0; windowIndex < embedded_raw_golden::kCount; ++windowIndex) {
    IMUSample samples[embedded_raw_golden::kWindowSamples] = {};
    for (uint16_t sampleIndex = 0; sampleIndex < embedded_raw_golden::kWindowSamples; ++sampleIndex) {
      const float* raw = embedded_raw_golden::kRaw[windowIndex][sampleIndex];
      samples[sampleIndex].accX = raw[0];
      samples[sampleIndex].accY = raw[1];
      samples[sampleIndex].accZ = raw[2];
      samples[sampleIndex].gyroX = raw[3];
      samples[sampleIndex].gyroY = raw[4];
      samples[sampleIndex].gyroZ = raw[5];
    }
    float actual[generated_leg_model::kFeatureCount] = {};
    TEST_ASSERT_TRUE(activity_classifier::extractFeaturesForSamples(
        samples, embedded_raw_golden::kWindowSamples, actual));
    for (uint8_t feature = 0; feature < generated_leg_model::kFeatureCount; ++feature) {
      const float expected = embedded_raw_golden::kExpectedFeatures[windowIndex][feature];
      const float tolerance = fmaxf(0.002f, fabsf(expected) * 0.0005f);
      TEST_ASSERT_FLOAT_WITHIN(tolerance, expected, actual[feature]);
    }
    const activity_classifier::Prediction predicted = activity_classifier::predictFeatures(actual);
    TEST_ASSERT_EQUAL_UINT8(embedded_raw_golden::kExpectedClass[windowIndex], predicted.classIndex);
  }
}

void testStepCounterRejectsStationarySamples() {
  step_counter::begin(true);
  step_counter::beginCapture();
  for (uint16_t index = 0; index < 520; ++index) {
    IMUSample sample = {};
    sample.timestampMs = sampleTimestamp(index);
    sample.gyroX = 1.0f;
    sample.gyroY = -3.5f;
    step_counter::push(sample);
    if (index == 259 || index == 389 || index == 519) {
      step_counter::classifyWindow(sample.timestampMs, true);
    }
  }
  step_counter::endCapture();
  TEST_ASSERT_EQUAL_UINT32(0, step_counter::totalSteps());
  TEST_ASSERT_EQUAL_UINT32(0, step_counter::droppedCandidates());
}

void testStepCounterRetroactivelyCountsWalkingWindow() {
  step_counter::begin(true);
  step_counter::beginCapture();
  pushSyntheticGait(520, 1.0f, true);
  TEST_ASSERT_GREATER_THAN_UINT32(0, step_counter::totalSteps());
  step_counter::endCapture();
  TEST_ASSERT_GREATER_OR_EQUAL_UINT32(18, step_counter::totalSteps());
  TEST_ASSERT_LESS_OR_EQUAL_UINT32(21, step_counter::totalSteps());
  TEST_ASSERT_EQUAL_UINT32(0, step_counter::droppedCandidates());
}

void testStepCounterActivityGateRejectsCyclingLikeMotion() {
  step_counter::begin(true);
  step_counter::beginCapture();
  pushSyntheticGait(520, 1.0f, false);
  step_counter::endCapture();
  TEST_ASSERT_EQUAL_UINT32(0, step_counter::totalSteps());
}

void testStepCounterTotalSurvivesCaptureRestart() {
  step_counter::begin(true);
  step_counter::beginCapture();
  pushSyntheticGait(520, 1.0f, true);
  step_counter::endCapture();
  const uint32_t first = step_counter::totalSteps();
  step_counter::beginCapture();
  pushSyntheticGait(520, 1.0f, true);
  step_counter::endCapture();
  TEST_ASSERT_GREATER_OR_EQUAL_UINT32(18, first);
  TEST_ASSERT_EQUAL_UINT32(first * 2, step_counter::totalSteps());
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(testGeneratedForestMatchesSklearnGoldenWindows);
  RUN_TEST(testClassifierIsEnabledOnlyForCalibratedGreenDevice);
  RUN_TEST(testWindowAndStrideSchedule);
  RUN_TEST(testRawCsvWindowsMatchPythonFeaturesAndPredictions);
  RUN_TEST(testStepCounterRejectsStationarySamples);
  RUN_TEST(testStepCounterRetroactivelyCountsWalkingWindow);
  RUN_TEST(testStepCounterActivityGateRejectsCyclingLikeMotion);
  RUN_TEST(testStepCounterTotalSurvivesCaptureRestart);
  return UNITY_END();
}
