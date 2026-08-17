#include "data_logger.h"

#include <Adafruit_SPIFlash.h>
#include <SdFat.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "app_config.h"

namespace {
#if defined(EXTERNAL_FLASH_USE_QSPI)
Adafruit_FlashTransport_QSPI flashTransport;
#elif defined(EXTERNAL_FLASH_USE_SPI)
Adafruit_FlashTransport_SPI flashTransport(EXTERNAL_FLASH_USE_CS, EXTERNAL_FLASH_USE_SPI);
#else
#error No external flash transport is defined for this board.
#endif

Adafruit_SPIFlash flash(&flashTransport);
// The XIAO nRF52840 Sense board variant uses a Puya P25Q16H QSPI flash chip.
const SPIFlash_Device_t kFlashDevices[] = {P25Q16H};
FatFileSystem fatfs;
FatFile logFile;
FatFile transferFile;
FatFile listRoot;
FatFile listEntry;
uint32_t samplesSinceFlush = 0;
uint32_t sessionIndex = 0;
char currentLogPathBuffer[48] = {0};
char transferFileNameBuffer[64] = {0};
char lastErrorBuffer[48] = {0};

void setError(const char* message) {
  strncpy(lastErrorBuffer, message, sizeof(lastErrorBuffer) - 1);
  lastErrorBuffer[sizeof(lastErrorBuffer) - 1] = '\0';
}

const char* normalizePath(const char* path) {
  return (path && path[0] == '/') ? path + 1 : path;
}

bool isCsvFile(const char* name) {
  const char* normalized = normalizePath(name);
  const char* extension = strrchr(normalized, '.');
  return extension && strcmp(extension, ".csv") == 0;
}

bool isSafeLogFileName(const char* name) {
  const char* normalized = normalizePath(name);
  if (!normalized || normalized[0] == '\0' || !isCsvFile(normalized)) {
    return false;
  }

  for (size_t i = 0; normalized[i] != '\0'; ++i) {
    const char ch = normalized[i];
    if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '.')) {
      return false;
    }
  }

  return strchr(normalized, '/') == nullptr && strchr(normalized, '\\') == nullptr;
}

bool isCurrentLogFile(const char* name) {
  return currentLogPathBuffer[0] != '\0' && strcmp(normalizePath(currentLogPathBuffer), normalizePath(name)) == 0;
}

bool isSafeLabelChar(char ch) {
  return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_';
}

void sanitizeLabelForPath(const char* label, char* output, size_t outputSize) {
  size_t outIndex = 0;
  for (size_t inIndex = 0; label && label[inIndex] != '\0' && outIndex + 1 < outputSize; ++inIndex) {
    const char ch = label[inIndex];
    if (isSafeLabelChar(ch)) {
      output[outIndex++] = ch;
    }
  }

  if (outIndex == 0 && outputSize > 1) {
    strncpy(output, "unlabeled", outputSize - 1);
    output[outputSize - 1] = '\0';
    return;
  }

  output[outIndex] = '\0';
}

uint32_t readSessionIndex() {
  FatFile indexFile;
  if (!indexFile.open(app_config::kSessionIndexPath, O_RDONLY)) {
    return 0;
  }

  char buffer[16] = {0};
  const int bytesRead = indexFile.read(buffer, sizeof(buffer) - 1);
  indexFile.close();
  if (bytesRead <= 0) {
    return 0;
  }

  return static_cast<uint32_t>(strtoul(buffer, nullptr, 10));
}

bool writeSessionIndex(uint32_t nextIndex) {
  if (fatfs.exists(app_config::kSessionIndexPath) && !fatfs.remove(app_config::kSessionIndexPath)) {
    setError("session_index_remove_failed");
    return false;
  }

  FatFile indexFile;
  if (!indexFile.open(app_config::kSessionIndexPath, O_RDWR | O_CREAT)) {
    setError("session_index_open_failed");
    return false;
  }

  char buffer[16] = {0};
  snprintf(buffer, sizeof(buffer), "%lu\n", static_cast<unsigned long>(nextIndex));
  const bool ok = indexFile.write(buffer, strlen(buffer)) == strlen(buffer);
  indexFile.flush();
  indexFile.close();
  if (!ok) {
    setError("session_index_write_failed");
  }
  return ok;
}

bool removeManagedFiles() {
  FatFile root;
  FatFile entry;
  bool ok = true;

  if (!root.open("/")) {
    setError("root_open_failed");
    return false;
  }

  while (entry.openNext(&root, O_RDONLY)) {
    char name[64] = {0};
    entry.getName(name, sizeof(name));
    entry.close();

    if (isCsvFile(name) && !fatfs.remove(name)) {
      ok = false;
    }
  }

  root.close();
  if (!ok) {
    setError("erase_failed");
  }
  return ok;
}
}

namespace data_logger {
bool begin() {
  clearError();

  if (!flash.begin(kFlashDevices, 1)) {
    setError("external_flash_begin_failed");
    return false;
  }

  if (!fatfs.begin(&flash)) {
    setError("external_fs_mount_failed");
    return false;
  }

  return true;
}

bool startSession(const char* label) {
  clearError();

  if (logFile.isOpen()) {
    logFile.flush();
    logFile.close();
  }

  sessionIndex = readSessionIndex();
  char safeLabel[app_config::kActivityLabelBufferSize] = {0};
  sanitizeLabelForPath(label, safeLabel, sizeof(safeLabel));
  while (true) {
    if (sessionIndex == UINT32_MAX) {
      setError("session_index_exhausted");
      return false;
    }
    snprintf(
        currentLogPathBuffer,
        sizeof(currentLogPathBuffer),
        "/%s_%04lu.csv",
        safeLabel,
        static_cast<unsigned long>(sessionIndex));
    if (!fatfs.exists(currentLogPathBuffer)) {
      break;
    }
    sessionIndex++;
  }

  if (!logFile.open(currentLogPathBuffer, O_RDWR | O_CREAT)) {
    setError("log_open_failed");
    return false;
  }

  if (logFile.write(app_config::kHeaderLine, strlen(app_config::kHeaderLine)) != strlen(app_config::kHeaderLine)) {
    setError("header_write_failed");
    logFile.close();
    return false;
  }

  logFile.flush();
  samplesSinceFlush = 0;

  if (!writeSessionIndex(sessionIndex + 1)) {
    logFile.close();
    fatfs.remove(currentLogPathBuffer);
    currentLogPathBuffer[0] = '\0';
    return false;
  }

  return true;
}

void stopSession() {
  if (!logFile.isOpen()) {
    return;
  }

  logFile.flush();
  logFile.close();
}

bool isLogging() {
  return logFile.isOpen();
}

bool writeSample(const IMUSample& sample, const char* label, bool mirrorToSerial, Stream& serial) {
  clearError();

  if (!logFile.isOpen()) {
    setError("log_not_open");
    return false;
  }

  char line[192] = {0};
  const int written = snprintf(
      line,
      sizeof(line),
      "%lu,%s,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n",
      static_cast<unsigned long>(sample.timestampMs),
      label,
      sample.accX,
      sample.accY,
      sample.accZ,
      sample.gyroX,
      sample.gyroY,
      sample.gyroZ);

  if (written <= 0 || written >= static_cast<int>(sizeof(line))) {
    setError("csv_format_failed");
    return false;
  }

  if (logFile.write(line, static_cast<size_t>(written)) != static_cast<size_t>(written)) {
    setError("flash_full_or_write_failed");
    stopSession();
    return false;
  }

  if (mirrorToSerial) {
    serial.print(line);
  }

  samplesSinceFlush++;
  return true;
}

void flushIfNeeded() {
  if (!logFile.isOpen() || samplesSinceFlush < app_config::kFlushEverySamples) {
    return;
  }

  logFile.flush();
  samplesSinceFlush = 0;
}

bool eraseLogs() {
  clearError();
  stopSession();
  endFileRead();
  endLogList();
  currentLogPathBuffer[0] = '\0';
  return removeManagedFiles();
}

bool loadSavedLabel(char* output, size_t outputSize) {
  clearError();
  if (!output || outputSize == 0) {
    setError("label_buffer_invalid");
    return false;
  }

  FatFile labelFile;
  if (!labelFile.open(app_config::kActivityLabelPath, O_RDONLY)) {
    setError("label_file_not_found");
    return false;
  }

  char buffer[app_config::kActivityLabelBufferSize] = {0};
  const int bytesRead = labelFile.read(buffer, sizeof(buffer) - 1);
  labelFile.close();
  if (bytesRead <= 0) {
    setError("label_read_failed");
    return false;
  }

  for (size_t i = 0; buffer[i] != '\0'; ++i) {
    if (buffer[i] == '\r' || buffer[i] == '\n') {
      buffer[i] = '\0';
      break;
    }
  }

  if (buffer[0] == '\0') {
    setError("label_empty");
    return false;
  }

  strncpy(output, buffer, outputSize - 1);
  output[outputSize - 1] = '\0';
  return true;
}

bool saveLabel(const char* label) {
  clearError();

  if (fatfs.exists(app_config::kActivityLabelPath) && !fatfs.remove(app_config::kActivityLabelPath)) {
    setError("label_remove_failed");
    return false;
  }

  FatFile labelFile;
  if (!labelFile.open(app_config::kActivityLabelPath, O_RDWR | O_CREAT)) {
    setError("label_open_failed");
    return false;
  }

  char line[app_config::kActivityLabelBufferSize + 2] = {0};
  snprintf(line, sizeof(line), "%s\n", label);
  const size_t length = strlen(line);
  const bool ok = labelFile.write(line, length) == length;
  labelFile.flush();
  labelFile.close();
  if (!ok) {
    setError("label_write_failed");
  }
  return ok;
}

void listFiles(Stream& serial) {
  FatFile root;
  FatFile entry;
  serial.println("files_begin");

  if (!root.open("/")) {
    serial.println("error,root_open_failed");
    serial.println("files_end");
    return;
  }

  while (entry.openNext(&root, O_RDONLY)) {
    char name[64] = {0};
    entry.getName(name, sizeof(name));
    serial.print(name);
    serial.print(',');
    serial.println(entry.fileSize());
    entry.close();
  }

  root.close();
  serial.println("files_end");
}

bool readFileToStream(const char* path, Stream& serial) {
  clearError();
  FatFile file;
  if (!file.open(path, O_RDONLY)) {
    setError("file_not_found");
    return false;
  }

  while (file.available()) {
    serial.write(file.read());
  }

  file.close();
  return true;
}

bool beginLogList() {
  clearError();
  endLogList();
  if (!listRoot.open("/")) {
    setError("root_open_failed");
    return false;
  }
  return true;
}

bool nextLogFile(LogFileInfo& info) {
  clearError();
  if (!listRoot.isOpen()) {
    setError("list_not_open");
    return false;
  }

  while (listEntry.openNext(&listRoot, O_RDONLY)) {
    char name[sizeof(info.name)] = {0};
    listEntry.getName(name, sizeof(name));
    const uint32_t sizeBytes = listEntry.fileSize();
    listEntry.close();

    if (!isCsvFile(name)) {
      continue;
    }

    strncpy(info.name, normalizePath(name), sizeof(info.name) - 1);
    info.name[sizeof(info.name) - 1] = '\0';
    info.sizeBytes = sizeBytes;
    info.active = logFile.isOpen() && isCurrentLogFile(info.name);
    return true;
  }

  return false;
}

void endLogList() {
  if (listEntry.isOpen()) {
    listEntry.close();
  }
  if (listRoot.isOpen()) {
    listRoot.close();
  }
}

bool getLogFileInfo(const char* path, LogFileInfo& info) {
  clearError();
  if (!isSafeLogFileName(path)) {
    setError("invalid_log_name");
    return false;
  }

  FatFile file;
  const char* normalized = normalizePath(path);
  if (!file.open(normalized, O_RDONLY)) {
    setError("file_not_found");
    return false;
  }

  strncpy(info.name, normalized, sizeof(info.name) - 1);
  info.name[sizeof(info.name) - 1] = '\0';
  info.sizeBytes = file.fileSize();
  info.active = logFile.isOpen() && isCurrentLogFile(normalized);
  file.close();
  return true;
}

bool deleteLogFile(const char* path) {
  clearError();
  if (!isSafeLogFileName(path)) {
    setError("invalid_log_name");
    return false;
  }

  const char* normalized = normalizePath(path);
  if (logFile.isOpen() && isCurrentLogFile(normalized)) {
    setError("file_is_active");
    return false;
  }
  if (transferFile.isOpen() && strcmp(transferFileNameBuffer, normalized) == 0) {
    setError("file_transfer_active");
    return false;
  }
  if (!fatfs.exists(normalized)) {
    setError("file_not_found");
    return false;
  }
  if (!fatfs.remove(normalized)) {
    setError("file_delete_failed");
    return false;
  }
  return true;
}

bool beginFileRead(const char* path, uint32_t offset, uint32_t& sizeBytes) {
  clearError();
  if (logFile.isOpen()) {
    setError("logging_active");
    return false;
  }
  if (!isSafeLogFileName(path)) {
    setError("invalid_log_name");
    return false;
  }

  endFileRead();
  const char* normalized = normalizePath(path);
  if (!transferFile.open(normalized, O_RDONLY)) {
    setError("file_not_found");
    return false;
  }

  sizeBytes = transferFile.fileSize();
  if (offset > sizeBytes || !transferFile.seekSet(offset)) {
    transferFile.close();
    setError("invalid_offset");
    return false;
  }

  strncpy(transferFileNameBuffer, normalized, sizeof(transferFileNameBuffer) - 1);
  transferFileNameBuffer[sizeof(transferFileNameBuffer) - 1] = '\0';
  return true;
}

int readFileChunk(uint8_t* buffer, size_t bufferSize) {
  if (!transferFile.isOpen() || !buffer || bufferSize == 0) {
    setError("file_read_not_open");
    return -1;
  }
  return transferFile.read(buffer, bufferSize);
}

void endFileRead() {
  if (transferFile.isOpen()) {
    transferFile.close();
  }
  transferFileNameBuffer[0] = '\0';
}

bool isFileReadOpen() {
  return transferFile.isOpen();
}

bool getStorageStats(uint32_t& totalBytes, uint32_t& usedBytes, uint32_t& freeBytes) {
  clearError();

  const int32_t freeClusters = fatfs.freeClusterCount();
  if (freeClusters < 0) {
    setError("storage_stats_failed");
    return false;
  }

  const uint32_t bytesPerCluster = fatfs.bytesPerCluster();
  const uint32_t clusterCount = fatfs.clusterCount();

  totalBytes = clusterCount * bytesPerCluster;
  freeBytes = static_cast<uint32_t>(freeClusters) * bytesPerCluster;
  usedBytes = totalBytes - freeBytes;
  return true;
}

const char* currentLogPath() {
  return currentLogPathBuffer;
}

const char* lastError() {
  return lastErrorBuffer[0] ? lastErrorBuffer : "none";
}

void clearError() {
  lastErrorBuffer[0] = '\0';
}
}
