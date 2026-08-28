#ifndef STEP_COUNTER_H
#define STEP_COUNTER_H

#include <stdint.h>

#include "app_types.h"

namespace step_counter {
void begin(bool enabled);
bool enabled();
void beginCapture();
void push(const IMUSample& sample);
uint8_t classifyWindow(uint32_t windowEndMs, bool stepActivity);
uint8_t endCapture();
uint32_t totalSteps();
#ifdef STEP_COUNTER_TESTING
uint8_t pendingCandidates();
uint32_t droppedCandidates();
#endif
}

#endif
