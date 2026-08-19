#include "crc32.h"

#include <stdio.h>

namespace crc32 {
uint32_t update(uint32_t state, const uint8_t* data, size_t length) {
  if (!data) {
    return state;
  }

  for (size_t i = 0; i < length; ++i) {
    state ^= data[i];
    for (uint8_t bit = 0; bit < 8; ++bit) {
      const uint32_t mask = static_cast<uint32_t>(-static_cast<int32_t>(state & 1U));
      state = (state >> 1U) ^ (0xEDB88320UL & mask);
    }
  }
  return state;
}

uint32_t finalize(uint32_t state) {
  return state ^ 0xFFFFFFFFUL;
}

uint32_t compute(const uint8_t* data, size_t length) {
  return finalize(update(kInitialValue, data, length));
}

void format(uint32_t value, char output[9]) {
  if (!output) {
    return;
  }
  snprintf(output, 9, "%08lX", static_cast<unsigned long>(value));
}
}
