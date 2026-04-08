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
uint32_t samplesSinceFlush = 0;
uint16_t sessionIndex = 0;
char currentLogPathBuffer[32] = {0};
char lastErrorBuffer[48] = {0};

void setError(const char* message) {
  strncpy(lastErrorBuffer, message, sizeof(lastErrorBuffer) - 1);
  lastErrorBuffer[sizeof(lastErrorBuffer) - 1] = '\0';
}

const char* normalizePath(const char* path) {
  return (path && path[0] == '/') ? path + 1 : path;
}

bool isManagedLogFile(const char* name) {
  const char* normalized = normalizePath(name);
  return strncmp(normalized, "log_", 4) == 0 || strcmp(normalized, "session_index.txt") == 0;
}

uint16_t readSessionIndex() {
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

  return static_cast<uint16_t>(atoi(buffer));
}

bool writeSessionIndex(uint16_t nextIndex) {
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
  snprintf(buffer, sizeof(buffer), "%u\n", nextIndex);
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

    if (isManagedLogFile(name) && !fatfs.remove(name)) {
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

bool startSession() {
  clearError();

  if (logFile.isOpen()) {
    logFile.flush();
    logFile.close();
  }

  sessionIndex = readSessionIndex();
  snprintf(currentLogPathBuffer, sizeof(currentLogPathBuffer), "/log_%04u.csv", sessionIndex);

  if (fatfs.exists(currentLogPathBuffer) && !fatfs.remove(currentLogPathBuffer)) {
    setError("old_log_remove_failed");
    return false;
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

  if (!writeSessionIndex(static_cast<uint16_t>(sessionIndex + 1))) {
    logFile.close();
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
      "%lu,%lu,%s,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.2f,%.2f,%.2f,%02X\n",
      static_cast<unsigned long>(sample.sampleId),
      static_cast<unsigned long>(sample.timestampMs),
      label,
      sample.accX,
      sample.accY,
      sample.accZ,
      sample.gyroX,
      sample.gyroY,
      sample.gyroZ,
      sample.rollDeg,
      sample.pitchDeg,
      sample.tempC,
      sample.imuAddress);

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
  currentLogPathBuffer[0] = '\0';
  return removeManagedFiles();
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
