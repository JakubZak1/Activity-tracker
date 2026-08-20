#include "device_identity.h"

#include <Arduino.h>
#include <stdio.h>

namespace {
char fullIdBuffer[17] = {0};
char shortIdBuffer[9] = {0};
char filePrefixBuffer[9] = {0};
char bleNameBuffer[32] = {0};
bool initialized = false;
uint32_t colorSeed = 0;
}

namespace device_identity {
void begin() {
  if (initialized) return;
  const uint32_t low = NRF_FICR->DEVICEID[0];
  const uint32_t high = NRF_FICR->DEVICEID[1];
  colorSeed = low ^ high;
  snprintf(
      fullIdBuffer,
      sizeof(fullIdBuffer),
      "%08lX%08lX",
      static_cast<unsigned long>(high),
      static_cast<unsigned long>(low));
  snprintf(shortIdBuffer, sizeof(shortIdBuffer), "%08lX", static_cast<unsigned long>(low));
  snprintf(filePrefixBuffer, sizeof(filePrefixBuffer), "%08lx", static_cast<unsigned long>(low));
  snprintf(bleNameBuffer, sizeof(bleNameBuffer), "ActivityTracker-%s", shortIdBuffer);
  initialized = true;
}

const char* fullId() {
  begin();
  return fullIdBuffer;
}

const char* shortId() {
  begin();
  return shortIdBuffer;
}

const char* filePrefix() {
  begin();
  return filePrefixBuffer;
}

const char* bleName() {
  begin();
  return bleNameBuffer;
}

bool prefersBlue() {
  begin();
  return (colorSeed & 1U) != 0;
}
}
