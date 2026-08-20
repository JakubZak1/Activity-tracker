#ifndef DEVICE_IDENTITY_H
#define DEVICE_IDENTITY_H

#include <stdint.h>

namespace device_identity {
void begin();
const char* fullId();
const char* shortId();
const char* filePrefix();
const char* bleName();
bool prefersBlue();
}

#endif
