#ifndef DATA_LOGGER_H
#define DATA_LOGGER_H

#include <Arduino.h>
#include "app_types.h"

namespace data_logger {
bool begin();
bool startSession(const char* label);
void stopSession();
bool isLogging();
bool writeSample(const IMUSample& sample, const char* label, bool mirrorToSerial, Stream& serial);
void flushIfNeeded();
bool eraseLogs();
bool loadSavedLabel(char* output, size_t outputSize);
bool saveLabel(const char* label);
void listFiles(Stream& serial);
bool readFileToStream(const char* path, Stream& serial);
bool getStorageStats(uint32_t& totalBytes, uint32_t& usedBytes, uint32_t& freeBytes);
const char* currentLogPath();
const char* lastError();
void clearError();
}

#endif
