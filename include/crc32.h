#ifndef CRC32_H
#define CRC32_H

#include <stddef.h>
#include <stdint.h>

namespace crc32 {
constexpr uint32_t kInitialValue = 0xFFFFFFFFUL;

uint32_t update(uint32_t state, const uint8_t* data, size_t length);
uint32_t finalize(uint32_t state);
uint32_t compute(const uint8_t* data, size_t length);
void format(uint32_t value, char output[9]);
}

#endif
