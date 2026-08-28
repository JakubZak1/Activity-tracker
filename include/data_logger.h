#ifndef DATA_LOGGER_H
#define DATA_LOGGER_H

#include <Arduino.h>
#include "app_types.h"

namespace data_logger {
struct LogFileInfo {
  char name[64];
  uint32_t sizeBytes;
  uint32_t crc32;
  bool hasCrc;
  bool complete;
};

enum class ListResult : uint8_t {
  Entry,
  End,
  Error,
};

enum class DeleteResult : uint8_t {
  Deleted,
  AlreadyDeleted,
  Error,
};

bool begin();
bool startSession(const char* label, const char* devicePrefix);
bool stopSession(LogFileInfo& info);
// Preserve an active recording as an incomplete .part file, releasing the
// unused preallocated tail. Used when acquisition fails before normal stop.
bool abortSession();
bool isLogging();
bool writeSample(const IMUSample& sample, const char* label, bool mirrorToSerial, Stream& serial);
bool flushIfNeeded();
void listFiles(Stream& serial);
bool readFileToStream(const char* path, Stream& serial);
bool beginLogList();
ListResult nextLogFile(LogFileInfo& info);
void endLogList();
bool getLogFileInfo(const char* path, LogFileInfo& info);
DeleteResult deleteLogFile(const char* path, uint32_t expectedSize, uint32_t expectedCrc);
bool beginFileRead(const char* path, uint32_t offset, LogFileInfo& info);
int readFileChunk(uint8_t* buffer, size_t bufferSize);
void endFileRead();
bool isFileReadOpen();
bool getStorageStats(uint32_t& totalBytes, uint32_t& usedBytes, uint32_t& freeBytes);
const char* currentLogPath();
const char* currentLabel();
uint32_t currentBytesWritten();
bool getLastCompletedSession(LogFileInfo& info);
const char* lastError();
void clearError();
}

#endif
