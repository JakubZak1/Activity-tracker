#ifndef DATA_LOGGER_H
#define DATA_LOGGER_H

#include <Arduino.h>
#include "app_types.h"

namespace data_logger {
bool begin();
bool startSession();
void stopSession();
bool isLogging();
bool writeSample(const IMUSample& sample, const char* label, bool mirrorToSerial, Stream& serial);
void flushIfNeeded();
bool eraseLogs();
void listFiles(Stream& serial);
bool readFileToStream(const char* path, Stream& serial);
bool getStorageStats(uint32_t& totalBytes, uint32_t& usedBytes, uint32_t& freeBytes);
const char* currentLogPath();
const char* lastError();
void clearError();
}

#endif
