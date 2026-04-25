#include "battery_reader.h"

#include <Arduino.h>

namespace {
constexpr float kAdcReferenceVoltage = 3.6f;
constexpr float kAdcMaxValue = 4095.0f;
constexpr float kBatteryDividerMultiplier = 2.961f;
constexpr uint8_t kSampleCount = 8;

uint8_t estimatePercent(uint16_t voltageMv) {
  struct Point {
    uint16_t mv;
    uint8_t percent;
  };

  constexpr Point kCurve[] = {
      {3300, 0},
      {3500, 10},
      {3600, 20},
      {3700, 40},
      {3800, 60},
      {3900, 75},
      {4000, 85},
      {4100, 95},
      {4200, 100},
  };

  if (voltageMv <= kCurve[0].mv) {
    return kCurve[0].percent;
  }

  for (size_t i = 1; i < sizeof(kCurve) / sizeof(kCurve[0]); ++i) {
    if (voltageMv <= kCurve[i].mv) {
      const Point& low = kCurve[i - 1];
      const Point& high = kCurve[i];
      const uint32_t voltageSpan = high.mv - low.mv;
      const uint32_t percentSpan = high.percent - low.percent;
      return low.percent + static_cast<uint8_t>(((voltageMv - low.mv) * percentSpan) / voltageSpan);
    }
  }

  return 100;
}
}

namespace battery_reader {
void begin() {
  pinMode(VBAT_ENABLE, OUTPUT);
  digitalWrite(VBAT_ENABLE, LOW);
  analogReference(AR_DEFAULT);
  analogReadResolution(12);
}

BatteryStatus readStatus() {
  uint32_t rawSum = 0;
  for (uint8_t i = 0; i < kSampleCount; ++i) {
    rawSum += analogRead(PIN_VBAT);
    delay(1);
  }

  const uint16_t raw = static_cast<uint16_t>(rawSum / kSampleCount);
  const float voltage = (static_cast<float>(raw) * kAdcReferenceVoltage * kBatteryDividerMultiplier) / kAdcMaxValue;
  const uint16_t voltageMv = static_cast<uint16_t>((voltage * 1000.0f) + 0.5f);

  BatteryStatus status = {
      raw,
      voltageMv,
      estimatePercent(voltageMv),
  };
  return status;
}
}

