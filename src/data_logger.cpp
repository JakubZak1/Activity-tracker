#include "data_logger.h"

#include <Adafruit_SPIFlash.h>
#include <SdFat.h>
#include <stdio.h>
#include <string.h>

#include "app_config.h"
#include "crc32.h"
#include "protocol_v3.h"

namespace {
#if defined(EXTERNAL_FLASH_USE_QSPI)
Adafruit_FlashTransport_QSPI flashTransport;
#elif defined(EXTERNAL_FLASH_USE_SPI)
Adafruit_FlashTransport_SPI flashTransport(EXTERNAL_FLASH_USE_CS, EXTERNAL_FLASH_USE_SPI);
#else
#error No external flash transport is defined for this board.
#endif

constexpr char kSessionIndexTemporaryPath[] = "/session_index.tmp";
constexpr char kPartSuffix[] = ".part";
constexpr char kCrcSuffix[] = ".crc";
constexpr char kCrcTemporarySuffix[] = ".crc.tmp";

enum class MetadataResult : uint8_t {
  Valid,
  Missing,
  Invalid,
  Error,
};

Adafruit_SPIFlash flash(&flashTransport);
const SPIFlash_Device_t kFlashDevices[] = {P25Q16H};
FatFileSystem fatfs;
FatFile logFile;
FatFile transferFile;
FatFile listRoot;
FatFile listEntry;

uint32_t samplesSinceFlush = 0;
uint32_t currentCrcState = crc32::kInitialValue;
uint32_t bytesWritten = 0;
char currentLogNameBuffer[64] = {0};
char currentPartPathBuffer[80] = {0};
char currentLabelBuffer[app_config::kActivityLabelBufferSize] = {0};
char transferFileNameBuffer[64] = {0};
char lastErrorBuffer[64] = {0};
data_logger::LogFileInfo lastCompletedSession = {};
bool hasLastCompletedSession = false;

void setError(const char* message) {
  strncpy(lastErrorBuffer, message ? message : "unknown", sizeof(lastErrorBuffer) - 1);
  lastErrorBuffer[sizeof(lastErrorBuffer) - 1] = '\0';
}

const char* normalizePath(const char* path) {
  return path && path[0] == '/' ? path + 1 : path;
}

bool makeRelatedPath(const char* name, const char* suffix, char* output, size_t outputSize) {
  if (!name || !suffix || !output || outputSize == 0) {
    return false;
  }
  const int written = snprintf(output, outputSize, "%s%s", normalizePath(name), suffix);
  return written > 0 && written < static_cast<int>(outputSize);
}

bool isPartName(const char* name) {
  if (!name) {
    return false;
  }
  const size_t length = strlen(name);
  const size_t suffixLength = strlen(kPartSuffix);
  return length > suffixLength && strcmp(name + length - suffixLength, kPartSuffix) == 0;
}

bool partNameToLogical(const char* partName, char* output, size_t outputSize) {
  if (!isPartName(partName) || !output || outputSize == 0) {
    return false;
  }
  const size_t logicalLength = strlen(partName) - strlen(kPartSuffix);
  if (logicalLength + 1 > outputSize) {
    return false;
  }
  memcpy(output, partName, logicalLength);
  output[logicalLength] = '\0';
  return protocol_v3::isManagedLogName(output);
}

bool readIndexFrom(const char* path, uint32_t& value) {
  FatFile file;
  if (!file.open(path, O_RDONLY)) {
    return false;
  }
  char buffer[16] = {0};
  const int bytesRead = file.read(buffer, sizeof(buffer) - 1);
  const bool closeOk = file.close();
  if (bytesRead <= 0 || !closeOk) {
    return false;
  }
  for (int i = 0; i < bytesRead; ++i) {
    if (buffer[i] == '\r' || buffer[i] == '\n') {
      buffer[i] = '\0';
      break;
    }
  }
  return protocol_v3::parseUnsigned32(buffer, value);
}

uint32_t readSessionIndex() {
  uint32_t value = 0;
  if (readIndexFrom(app_config::kSessionIndexPath, value)) {
    return value;
  }
  if (readIndexFrom(kSessionIndexTemporaryPath, value)) {
    return value;
  }
  return 0;
}

bool writeSessionIndex(uint32_t nextIndex) {
  if (fatfs.exists(kSessionIndexTemporaryPath) && !fatfs.remove(kSessionIndexTemporaryPath)) {
    setError("session_index_temp_remove_failed");
    return false;
  }

  FatFile file;
  if (!file.open(kSessionIndexTemporaryPath, O_RDWR | O_CREAT | O_TRUNC)) {
    setError("session_index_open_failed");
    return false;
  }
  char line[16] = {0};
  const int length = snprintf(line, sizeof(line), "%lu\n", static_cast<unsigned long>(nextIndex));
  const bool writeOk = length > 0 && length < static_cast<int>(sizeof(line)) &&
                       file.write(line, static_cast<size_t>(length)) == static_cast<size_t>(length);
  const bool syncOk = writeOk && file.sync();
  const bool closeOk = file.close();
  if (!writeOk || !syncOk || !closeOk) {
    setError(!writeOk ? "session_index_write_failed"
                      : (!syncOk ? "session_index_sync_failed" : "session_index_close_failed"));
    return false;
  }

  if (fatfs.exists(app_config::kSessionIndexPath) && !fatfs.remove(app_config::kSessionIndexPath)) {
    setError("session_index_replace_failed");
    return false;
  }
  if (!fatfs.rename(kSessionIndexTemporaryPath, app_config::kSessionIndexPath)) {
    setError("session_index_rename_failed");
    return false;
  }
  return true;
}

bool candidateExists(const char* logicalName) {
  char related[80] = {0};
  if (fatfs.exists(logicalName)) {
    return true;
  }
  return (makeRelatedPath(logicalName, kPartSuffix, related, sizeof(related)) && fatfs.exists(related)) ||
         (makeRelatedPath(logicalName, kCrcSuffix, related, sizeof(related)) && fatfs.exists(related)) ||
         (makeRelatedPath(logicalName, kCrcTemporarySuffix, related, sizeof(related)) && fatfs.exists(related));
}

bool chooseSessionName(const char* label) {
  uint32_t index = readSessionIndex();
  while (true) {
    const int written = snprintf(
        currentLogNameBuffer,
        sizeof(currentLogNameBuffer),
        "%s_%lu.csv",
        label,
        static_cast<unsigned long>(index));
    if (written <= 0 || written >= static_cast<int>(sizeof(currentLogNameBuffer))) {
      setError("session_name_too_long");
      return false;
    }
    if (!candidateExists(currentLogNameBuffer)) {
      break;
    }
    if (index == UINT32_MAX) {
      setError("session_index_exhausted");
      return false;
    }
    ++index;
  }
  if (index == UINT32_MAX) {
    setError("session_index_exhausted");
    return false;
  }
  if (!makeRelatedPath(currentLogNameBuffer, kPartSuffix, currentPartPathBuffer, sizeof(currentPartPathBuffer))) {
    setError("session_name_too_long");
    return false;
  }
  return writeSessionIndex(index + 1);
}

void closeIncomplete(const char* errorCode) {
  if (logFile.isOpen()) {
    logFile.sync();
    logFile.close();
  }
  setError(errorCode);
}

bool computeFileCrc(const char* path, uint32_t& sizeBytes, uint32_t& value) {
  FatFile file;
  if (!file.open(path, O_RDONLY)) {
    setError("file_not_found");
    return false;
  }
  sizeBytes = file.fileSize();
  uint32_t remaining = sizeBytes;
  uint32_t state = crc32::kInitialValue;
  uint8_t buffer[128] = {0};
  while (remaining > 0) {
    const size_t requested = remaining < sizeof(buffer) ? static_cast<size_t>(remaining) : sizeof(buffer);
    const int bytesRead = file.read(buffer, requested);
    if (bytesRead <= 0) {
      file.close();
      setError("file_crc_read_failed");
      return false;
    }
    state = crc32::update(state, buffer, static_cast<size_t>(bytesRead));
    remaining -= static_cast<uint32_t>(bytesRead);
  }
  if (!file.close()) {
    setError("file_crc_close_failed");
    return false;
  }
  value = crc32::finalize(state);
  return true;
}

bool writeCrcMetadata(const char* name, uint32_t sizeBytes, uint32_t value) {
  char temporaryPath[80] = {0};
  char finalPath[80] = {0};
  if (!makeRelatedPath(name, kCrcTemporarySuffix, temporaryPath, sizeof(temporaryPath)) ||
      !makeRelatedPath(name, kCrcSuffix, finalPath, sizeof(finalPath))) {
    setError("metadata_name_too_long");
    return false;
  }
  if (fatfs.exists(temporaryPath) && !fatfs.remove(temporaryPath)) {
    setError("metadata_temp_remove_failed");
    return false;
  }
  FatFile file;
  if (!file.open(temporaryPath, O_RDWR | O_CREAT | O_TRUNC)) {
    setError("metadata_open_failed");
    return false;
  }
  char crcText[9] = {0};
  crc32::format(value, crcText);
  char line[32] = {0};
  const int length = snprintf(line, sizeof(line), "%lu,%s\n", static_cast<unsigned long>(sizeBytes), crcText);
  const bool writeOk = length > 0 && length < static_cast<int>(sizeof(line)) &&
                       file.write(line, static_cast<size_t>(length)) == static_cast<size_t>(length);
  const bool syncOk = writeOk && file.sync();
  const bool closeOk = file.close();
  if (!writeOk || !syncOk || !closeOk) {
    setError(!writeOk ? "metadata_write_failed" : (!syncOk ? "metadata_sync_failed" : "metadata_close_failed"));
    return false;
  }
  if (fatfs.exists(finalPath) && !fatfs.remove(finalPath)) {
    setError("metadata_replace_failed");
    return false;
  }
  if (!fatfs.rename(temporaryPath, finalPath)) {
    setError("metadata_rename_failed");
    return false;
  }
  return true;
}

MetadataResult readCrcMetadata(const char* name, uint32_t& sizeBytes, uint32_t& value) {
  char path[80] = {0};
  if (!makeRelatedPath(name, kCrcSuffix, path, sizeof(path))) {
    setError("metadata_name_too_long");
    return MetadataResult::Error;
  }
  if (!fatfs.exists(path)) {
    return MetadataResult::Missing;
  }

  FatFile file;
  if (!file.open(path, O_RDONLY)) {
    setError("metadata_open_failed");
    return MetadataResult::Error;
  }
  const uint32_t metadataSize = file.fileSize();
  char line[32] = {0};
  if (metadataSize == 0 || metadataSize >= sizeof(line)) {
    file.close();
    return MetadataResult::Invalid;
  }
  const int bytesRead = file.read(line, static_cast<size_t>(metadataSize));
  const bool closeOk = file.close();
  if (bytesRead != static_cast<int>(metadataSize) || !closeOk) {
    setError(bytesRead != static_cast<int>(metadataSize) ? "metadata_read_failed" : "metadata_close_failed");
    return MetadataResult::Error;
  }
  if (line[metadataSize - 1] != '\n' ||
      memchr(line, '\0', static_cast<size_t>(metadataSize)) != nullptr ||
      memchr(line, '\n', static_cast<size_t>(metadataSize - 1)) != nullptr ||
      memchr(line, '\r', static_cast<size_t>(metadataSize)) != nullptr) {
    return MetadataResult::Invalid;
  }
  line[metadataSize - 1] = '\0';
  uint32_t parsedSize = 0;
  uint32_t parsedCrc = 0;
  if (!protocol_v3::parseFileMetadata(line, parsedSize, parsedCrc)) {
    return MetadataResult::Invalid;
  }
  sizeBytes = parsedSize;
  value = parsedCrc;
  return MetadataResult::Valid;
}

bool readFileSize(const char* path, uint32_t& sizeBytes) {
  FatFile file;
  if (!file.open(path, O_RDONLY)) {
    setError("file_not_found");
    return false;
  }
  sizeBytes = file.fileSize();
  if (!file.close()) {
    setError("file_close_failed");
    return false;
  }
  return true;
}

bool fillIncompleteInfo(const char* logicalName, const char* partPath, data_logger::LogFileInfo& info) {
  FatFile file;
  if (!file.open(partPath, O_RDONLY)) {
    setError("incomplete_file_open_failed");
    return false;
  }
  const uint32_t size = file.fileSize();
  if (!file.close()) {
    setError("incomplete_file_close_failed");
    return false;
  }
  strncpy(info.name, logicalName, sizeof(info.name) - 1);
  info.name[sizeof(info.name) - 1] = '\0';
  info.sizeBytes = size;
  info.crc32 = 0;
  info.hasCrc = false;
  info.complete = false;
  return true;
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
  currentLogNameBuffer[0] = '\0';
  currentPartPathBuffer[0] = '\0';
  currentLabelBuffer[0] = '\0';
  return true;
}

bool startSession(const char* label) {
  clearError();
  if (!protocol_v3::isValidLabel(label)) {
    setError("invalid_label");
    return false;
  }
  if (logFile.isOpen()) {
    setError("already_recording");
    return false;
  }
  if (!chooseSessionName(label)) {
    return false;
  }
  if (!logFile.open(currentPartPathBuffer, O_RDWR | O_CREAT | O_TRUNC)) {
    setError("log_open_failed");
    return false;
  }

  strncpy(currentLabelBuffer, label, sizeof(currentLabelBuffer) - 1);
  currentLabelBuffer[sizeof(currentLabelBuffer) - 1] = '\0';
  currentCrcState = crc32::kInitialValue;
  bytesWritten = 0;
  samplesSinceFlush = 0;

  const size_t headerLength = strlen(app_config::kHeaderLine);
  const size_t written = logFile.write(app_config::kHeaderLine, headerLength);
  if (written > 0) {
    currentCrcState = crc32::update(
        currentCrcState, reinterpret_cast<const uint8_t*>(app_config::kHeaderLine), written);
    bytesWritten += static_cast<uint32_t>(written);
  }
  if (written != headerLength) {
    closeIncomplete("header_write_failed");
    return false;
  }
  if (!logFile.sync()) {
    closeIncomplete("header_sync_failed");
    return false;
  }
  return true;
}

bool stopSession(LogFileInfo& info) {
  clearError();
  if (!logFile.isOpen()) {
    setError("not_recording");
    return false;
  }
  const bool syncOk = logFile.sync();
  const uint32_t finalSize = logFile.fileSize();
  const uint32_t expectedCrc = crc32::finalize(currentCrcState);
  const bool closeOk = logFile.close();
  if (!syncOk || !closeOk) {
    setError(!syncOk ? "log_sync_failed" : "log_close_failed");
    return false;
  }
  if (finalSize != bytesWritten) {
    setError("log_size_mismatch");
    return false;
  }
  uint32_t verifiedSize = 0;
  uint32_t verifiedCrc = 0;
  if (!computeFileCrc(currentPartPathBuffer, verifiedSize, verifiedCrc)) {
    return false;
  }
  if (verifiedSize != finalSize || verifiedCrc != expectedCrc) {
    setError("log_crc_mismatch");
    return false;
  }
  if (!writeCrcMetadata(currentLogNameBuffer, verifiedSize, verifiedCrc)) {
    return false;
  }
  if (!fatfs.rename(currentPartPathBuffer, currentLogNameBuffer)) {
    setError("log_finalize_rename_failed");
    return false;
  }

  memset(&info, 0, sizeof(info));
  strncpy(info.name, currentLogNameBuffer, sizeof(info.name) - 1);
  info.sizeBytes = verifiedSize;
  info.crc32 = verifiedCrc;
  info.hasCrc = true;
  info.complete = true;
  lastCompletedSession = info;
  hasLastCompletedSession = true;
  currentPartPathBuffer[0] = '\0';
  return true;
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
  if (!label || strcmp(label, currentLabelBuffer) != 0) {
    closeIncomplete("label_changed_during_recording");
    return false;
  }

  char line[192] = {0};
  const int length = snprintf(
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
  if (length <= 0 || length >= static_cast<int>(sizeof(line))) {
    closeIncomplete("csv_format_failed");
    return false;
  }

  const size_t written = logFile.write(line, static_cast<size_t>(length));
  if (written > 0) {
    currentCrcState = crc32::update(currentCrcState, reinterpret_cast<const uint8_t*>(line), written);
    bytesWritten += static_cast<uint32_t>(written);
  }
  if (written != static_cast<size_t>(length)) {
    closeIncomplete("flash_full_or_write_failed");
    return false;
  }
  if (mirrorToSerial) {
    serial.print(line);
  }
  ++samplesSinceFlush;
  return true;
}

bool flushIfNeeded() {
  if (!logFile.isOpen() || samplesSinceFlush < app_config::kFlushEverySamples) {
    return true;
  }
  if (!logFile.sync()) {
    closeIncomplete("log_sync_failed");
    return false;
  }
  samplesSinceFlush = 0;
  return true;
}

void listFiles(Stream& serial) {
  if (!beginLogList()) {
    serial.print("error,list,");
    serial.println(lastError());
    return;
  }
  serial.println("files_begin");
  LogFileInfo info = {};
  while (true) {
    const ListResult result = nextLogFile(info);
    if (result == ListResult::End) {
      break;
    }
    if (result == ListResult::Error) {
      serial.print("error,list,");
      serial.println(lastError());
      break;
    }
    char crcText[9] = {0};
    if (info.hasCrc) {
      crc32::format(info.crc32, crcText);
    }
    serial.print(info.name);
    serial.print(',');
    serial.print(info.sizeBytes);
    serial.print(',');
    serial.print(info.hasCrc ? crcText : "none");
    serial.print(',');
    serial.println(info.complete ? "complete" : "incomplete");
  }
  endLogList();
  serial.println("files_end");
}

bool readFileToStream(const char* path, Stream& serial) {
  clearError();
  LogFileInfo info = {};
  if (!getLogFileInfo(path, info) || !info.complete) {
    if (!info.complete && strcmp(lastError(), "none") == 0) {
      setError("file_incomplete");
    }
    return false;
  }
  FatFile file;
  if (!file.open(normalizePath(path), O_RDONLY)) {
    setError("file_not_found");
    return false;
  }
  uint8_t buffer[128] = {0};
  uint32_t remaining = info.sizeBytes;
  while (remaining > 0) {
    const size_t requested = remaining < sizeof(buffer) ? static_cast<size_t>(remaining) : sizeof(buffer);
    const int bytesRead = file.read(buffer, requested);
    if (bytesRead <= 0) {
      file.close();
      setError("file_read_failed");
      return false;
    }
    serial.write(buffer, static_cast<size_t>(bytesRead));
    remaining -= static_cast<uint32_t>(bytesRead);
  }
  if (!file.close()) {
    setError("file_read_close_failed");
    return false;
  }
  return true;
}

bool beginLogList() {
  clearError();
  endLogList();
  if (!listRoot.open("/")) {
    setError("root_open_failed");
    return false;
  }
  listRoot.clearError();
  return true;
}

ListResult nextLogFile(LogFileInfo& info) {
  clearError();
  if (!listRoot.isOpen()) {
    setError("list_not_open");
    return ListResult::Error;
  }
  while (listEntry.openNext(&listRoot, O_RDONLY)) {
    char storedName[80] = {0};
    listEntry.getName(storedName, sizeof(storedName));
    if (!listEntry.close()) {
      setError("list_entry_close_failed");
      return ListResult::Error;
    }

    if (protocol_v3::isManagedLogName(storedName)) {
      return getLogFileInfo(storedName, info) ? ListResult::Entry : ListResult::Error;
    }
    char logicalName[64] = {0};
    if (!partNameToLogical(storedName, logicalName, sizeof(logicalName))) {
      continue;
    }
    if (fatfs.exists(logicalName)) {
      continue;
    }
    return fillIncompleteInfo(logicalName, storedName, info) ? ListResult::Entry : ListResult::Error;
  }
  if (listRoot.getError()) {
    setError("list_iteration_failed");
    return ListResult::Error;
  }
  return ListResult::End;
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
  const char* normalized = normalizePath(path);
  if (!protocol_v3::isManagedLogName(normalized)) {
    setError("invalid_name");
    return false;
  }
  memset(&info, 0, sizeof(info));
  strncpy(info.name, normalized, sizeof(info.name) - 1);

  if (fatfs.exists(normalized)) {
    uint32_t actualSize = 0;
    if (!readFileSize(normalized, actualSize)) {
      return false;
    }
    uint32_t metadataSize = 0;
    uint32_t metadataCrc = 0;
    const MetadataResult metadata = readCrcMetadata(normalized, metadataSize, metadataCrc);
    if (metadata == MetadataResult::Error) {
      return false;
    }
    info.sizeBytes = actualSize;
    if (metadata == MetadataResult::Valid && metadataSize == actualSize) {
      info.crc32 = metadataCrc;
      info.hasCrc = true;
      info.complete = true;
    } else {
      // A final-looking CSV without valid, size-matched metadata is legacy,
      // interrupted, or corrupt. Never mint a new CRC identity for it.
      info.crc32 = 0;
      info.hasCrc = false;
      info.complete = false;
    }
    return true;
  }

  char partPath[80] = {0};
  if (!makeRelatedPath(normalized, kPartSuffix, partPath, sizeof(partPath))) {
    setError("invalid_name");
    return false;
  }
  if (fatfs.exists(partPath)) {
    return fillIncompleteInfo(normalized, partPath, info);
  }
  setError("file_not_found");
  return false;
}

DeleteResult deleteLogFile(const char* path, uint32_t expectedSize, uint32_t expectedCrc) {
  clearError();
  const char* normalized = normalizePath(path);
  if (!protocol_v3::isManagedLogName(normalized)) {
    setError("invalid_name");
    return DeleteResult::Error;
  }
  if (strcmp(currentLogNameBuffer, normalized) == 0 && logFile.isOpen()) {
    setError("file_is_active");
    return DeleteResult::Error;
  }
  if (transferFile.isOpen() && strcmp(transferFileNameBuffer, normalized) == 0) {
    setError("file_transfer_active");
    return DeleteResult::Error;
  }

  char partPath[80] = {0};
  makeRelatedPath(normalized, kPartSuffix, partPath, sizeof(partPath));
  if (!fatfs.exists(normalized)) {
    if (fatfs.exists(partPath)) {
      setError("file_incomplete");
      return DeleteResult::Error;
    }
    char metadataPath[80] = {0};
    if (makeRelatedPath(normalized, kCrcSuffix, metadataPath, sizeof(metadataPath)) && fatfs.exists(metadataPath)) {
      fatfs.remove(metadataPath);
    }
    if (hasLastCompletedSession && strcmp(lastCompletedSession.name, normalized) == 0) {
      memset(&lastCompletedSession, 0, sizeof(lastCompletedSession));
      hasLastCompletedSession = false;
    }
    return DeleteResult::AlreadyDeleted;
  }

  LogFileInfo info = {};
  if (!getLogFileInfo(normalized, info)) {
    return DeleteResult::Error;
  }
  if (!info.complete || !info.hasCrc || info.sizeBytes != expectedSize || info.crc32 != expectedCrc) {
    setError("file_identity_mismatch");
    return DeleteResult::Error;
  }
  uint32_t actualSize = 0;
  uint32_t actualCrc = 0;
  if (!computeFileCrc(normalized, actualSize, actualCrc)) {
    return DeleteResult::Error;
  }
  if (actualSize != info.sizeBytes || actualCrc != info.crc32) {
    setError("file_corrupt");
    return DeleteResult::Error;
  }
  if (!fatfs.remove(normalized)) {
    setError("file_delete_failed");
    return DeleteResult::Error;
  }
  char metadataPath[80] = {0};
  if (makeRelatedPath(normalized, kCrcSuffix, metadataPath, sizeof(metadataPath)) && fatfs.exists(metadataPath)) {
    fatfs.remove(metadataPath);
  }
  if (hasLastCompletedSession && strcmp(lastCompletedSession.name, normalized) == 0) {
    memset(&lastCompletedSession, 0, sizeof(lastCompletedSession));
    hasLastCompletedSession = false;
  }
  return DeleteResult::Deleted;
}

bool beginFileRead(const char* path, uint32_t offset, LogFileInfo& info) {
  clearError();
  if (logFile.isOpen()) {
    setError("busy_recording");
    return false;
  }
  if (!getLogFileInfo(path, info)) {
    return false;
  }
  if (!info.complete || !info.hasCrc) {
    setError("file_incomplete");
    return false;
  }
  if (offset > info.sizeBytes) {
    setError("invalid_offset");
    return false;
  }

  endFileRead();
  if (!transferFile.open(info.name, O_RDONLY)) {
    setError("file_not_found");
    return false;
  }
  if (!transferFile.seekSet(offset)) {
    transferFile.close();
    setError("invalid_offset");
    return false;
  }
  strncpy(transferFileNameBuffer, info.name, sizeof(transferFileNameBuffer) - 1);
  transferFileNameBuffer[sizeof(transferFileNameBuffer) - 1] = '\0';
  return true;
}

int readFileChunk(uint8_t* buffer, size_t bufferSize) {
  if (!transferFile.isOpen() || !buffer || bufferSize == 0) {
    setError("file_read_not_open");
    return -1;
  }
  const int result = transferFile.read(buffer, bufferSize);
  if (result < 0) {
    setError("file_read_failed");
  }
  return result;
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
  return currentLogNameBuffer;
}

const char* currentLabel() {
  return currentLabelBuffer;
}

uint32_t currentBytesWritten() {
  return bytesWritten;
}

bool getLastCompletedSession(LogFileInfo& info) {
  if (!hasLastCompletedSession) {
    return false;
  }
  info = lastCompletedSession;
  return true;
}

const char* lastError() {
  return lastErrorBuffer[0] ? lastErrorBuffer : "none";
}

void clearError() {
  lastErrorBuffer[0] = '\0';
}
}
