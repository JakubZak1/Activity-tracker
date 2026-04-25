#ifndef BATTERY_READER_H
#define BATTERY_READER_H

#include <stdint.h>

struct BatteryStatus {
  uint16_t rawAdc;
  uint16_t voltageMv;
  uint8_t percent;
};

namespace battery_reader {
void begin();
BatteryStatus readStatus();
}

#endif
