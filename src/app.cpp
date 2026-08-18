#include "app.h"

#include <Adafruit_TinyUSB.h>
#include <Arduino.h>
#include <stdio.h>
#include <string.h>

#include "activity_state.h"
#include "app_config.h"
#include "battery_reader.h"
#include "ble_service.h"
#include "crc32.h"
#include "data_logger.h"
#include "imu_reader.h"
#include "protocol_v3.h"
#include "serial_console.h"

namespace {
enum class ResponseTransport : uint8_t {
  Ble,
  Serial,
};

activity_state::RecordingMachine recordingMachine;
uint32_t recordingStartedAtMs = 0;
uint32_t nextSampleMs = 0;
uint32_t sampleId = 0;
bool serialStreaming = false;
bool serialFaultReported = false;
bool bleFaultReported = false;
bool faultBleWasConnected = false;
char recordingFault[64] = {0};
data_logger::LogFileInfo pendingOffload = {};
bool hasPendingOffload = false;
char pendingOffloadLabel[app_config::kActivityLabelBufferSize] = {0};
uint32_t cachedFreeBytes = 0;
uint32_t recordingFreeBytesAtStart = 0;

void setLed(bool on) {
  digitalWrite(LED_BUILTIN, on ? LOW : HIGH);
}

void blinkFatalPattern(uint32_t onMs, uint32_t offMs) {
  while (true) {
    setLed(true);
    delay(onMs);
    setLed(false);
    delay(offMs);
  }
}

void waitForSerial(uint32_t timeoutMs) {
  const uint32_t start = millis();
  while (!Serial && millis() - start < timeoutMs) {
    delay(10);
  }
}

void handleFatalError(const char* message) {
  if (Serial) {
    Serial.println(message);
  }
  blinkFatalPattern(120, 880);
}

void resetSamplingRuntime() {
  sampleId = 0;
  nextSampleMs = millis();
  recordingStartedAtMs = nextSampleMs;
  serialFaultReported = false;
  bleFaultReported = false;
  faultBleWasConnected = false;
  setLed(false);
}

void copyFault(const char* error) {
  strncpy(recordingFault, error ? error : "recording_failed", sizeof(recordingFault) - 1);
  recordingFault[sizeof(recordingFault) - 1] = '\0';
  serialFaultReported = false;
  bleFaultReported = false;
  faultBleWasConnected = ble_service::isConnected();
  recordingMachine.fail();
  setLed(false);
}

bool sendResponse(ResponseTransport transport, Stream* serial, const char* response) {
  if (transport == ResponseTransport::Ble) {
    return ble_service::sendControlResponse(response);
  }
  if (serial) {
    serial->println(response);
    return true;
  }
  return false;
}

void sendError(
    ResponseTransport transport,
    Stream* serial,
    uint32_t requestId,
    const char* errorCode) {
  char response[app_config::kBleControlResponseBufferSize] = {0};
  snprintf(
      response,
      sizeof(response),
      "error,%lu,%s",
      static_cast<unsigned long>(requestId),
      errorCode ? errorCode : "unknown");
  sendResponse(transport, serial, response);
}

bool getFreeBytes(uint32_t& freeBytes) {
  uint32_t totalBytes = 0;
  uint32_t usedBytes = 0;
  return data_logger::getStorageStats(totalBytes, usedBytes, freeBytes);
}

bool refreshCachedFreeBytes() {
  return getFreeBytes(cachedFreeBytes);
}

void sendHello(ResponseTransport transport, Stream* serial, uint32_t requestId) {
  char response[128] = {0};
  snprintf(
      response,
      sizeof(response),
      "ok,%lu,hello,5,recording;catalog;download;resume;crc32;segmentation;auto_offload;pause_offload",
      static_cast<unsigned long>(requestId));
  sendResponse(transport, serial, response);
}

void sendStatus(ResponseTransport transport, Stream* serial, uint32_t requestId) {
  uint32_t freeBytes = cachedFreeBytes;
  if (recordingMachine.state() != activity_state::RecordingState::Recording &&
      !refreshCachedFreeBytes()) {
    sendError(transport, serial, requestId, data_logger::lastError());
    return;
  }
  freeBytes = cachedFreeBytes;

  char response[app_config::kBleControlResponseBufferSize] = {0};
  switch (recordingMachine.state()) {
    case activity_state::RecordingState::Idle: {
      data_logger::LogFileInfo last = {};
      if (data_logger::getLastCompletedSession(last)) {
        char crcText[9] = {0};
        crc32::format(last.crc32, crcText);
        snprintf(
            response,
            sizeof(response),
            "status,%lu,idle,%s,%lu,%s,%lu",
            static_cast<unsigned long>(requestId),
            last.name,
            static_cast<unsigned long>(last.sizeBytes),
            crcText,
            static_cast<unsigned long>(freeBytes));
      } else {
        snprintf(
            response,
            sizeof(response),
            "status,%lu,idle,none,0,none,%lu",
            static_cast<unsigned long>(requestId),
            static_cast<unsigned long>(freeBytes));
      }
      break;
    }
    case activity_state::RecordingState::Recording:
      freeBytes = data_logger::currentBytesWritten() < recordingFreeBytesAtStart
                      ? recordingFreeBytesAtStart - data_logger::currentBytesWritten()
                      : 0;
      snprintf(
          response,
          sizeof(response),
          "status,%lu,recording,%s,%s,%lu,%lu,%lu",
          static_cast<unsigned long>(requestId),
          data_logger::currentLabel(),
          data_logger::currentLogPath(),
          static_cast<unsigned long>(millis() - recordingStartedAtMs),
          static_cast<unsigned long>(data_logger::currentBytesWritten()),
          static_cast<unsigned long>(freeBytes));
      break;
    case activity_state::RecordingState::PausedForOffload: {
      if (!hasPendingOffload) {
        sendError(transport, serial, requestId, "paused_file_missing");
        return;
      }
      char crcText[9] = {0};
      crc32::format(pendingOffload.crc32, crcText);
      snprintf(
          response,
          sizeof(response),
          "status,%lu,paused,%s,%s,%lu,%s,%lu,%lu",
          static_cast<unsigned long>(requestId),
          data_logger::currentLabel(),
          pendingOffload.name,
          static_cast<unsigned long>(pendingOffload.sizeBytes),
          crcText,
          static_cast<unsigned long>(millis() - recordingStartedAtMs),
          static_cast<unsigned long>(freeBytes));
      break;
    }
    case activity_state::RecordingState::Fault:
      snprintf(
          response,
          sizeof(response),
          "status,%lu,fault,%s,%s,%lu",
          static_cast<unsigned long>(requestId),
          recordingFault[0] ? recordingFault : "recording_failed",
          data_logger::currentLogPath()[0] ? data_logger::currentLogPath() : "none",
          static_cast<unsigned long>(freeBytes));
      break;
  }
  sendResponse(transport, serial, response);
  if (transport == ResponseTransport::Ble) {
    ble_service::requestTelemetry();
  }
}

void handleRecordStart(
    ResponseTransport transport,
    Stream* serial,
    const protocol_v3::Command& command) {
  const activity_state::RecordStartDecision decision = activity_state::decideRecordStart(
      recordingMachine.state(), data_logger::currentLabel(), command.label);
  if (decision == activity_state::RecordStartDecision::Fault) {
    sendError(transport, serial, command.requestId, "fault_requires_restart");
    return;
  }
  if (decision == activity_state::RecordStartDecision::Conflict) {
    sendError(transport, serial, command.requestId, "already_recording");
    return;
  }
  if (decision == activity_state::RecordStartDecision::Replay) {
    char response[128] = {0};
    snprintf(
        response,
        sizeof(response),
        "ok,%lu,already_recording,%s,%s",
        static_cast<unsigned long>(command.requestId),
        command.label,
        data_logger::currentLogPath());
    sendResponse(transport, serial, response);
    return;
  }
  if (ble_service::isFileOperationActive()) {
    sendError(transport, serial, command.requestId, "file_operation_busy");
    return;
  }
  if (!refreshCachedFreeBytes()) {
    sendError(transport, serial, command.requestId, data_logger::lastError());
    return;
  }
  if (cachedFreeBytes <= app_config::kCriticalFreeBytes) {
    sendError(transport, serial, command.requestId, "storage_critical");
    return;
  }
  if (!data_logger::startSession(command.label)) {
    copyFault(data_logger::lastError());
    sendError(transport, serial, command.requestId, recordingFault);
    return;
  }
  if (!recordingMachine.begin()) {
    copyFault("recording_state_error");
    sendError(transport, serial, command.requestId, recordingFault);
    return;
  }
  recordingFreeBytesAtStart = cachedFreeBytes;
  resetSamplingRuntime();
  char response[128] = {0};
  snprintf(
      response,
      sizeof(response),
      "ok,%lu,recording_started,%s,%s",
      static_cast<unsigned long>(command.requestId),
      command.label,
      data_logger::currentLogPath());
  sendResponse(transport, serial, response);
}

void handleRecordStop(
    ResponseTransport transport,
    Stream* serial,
    const protocol_v3::Command& command) {
  if (recordingMachine.state() == activity_state::RecordingState::Fault) {
    sendError(
        transport,
        serial,
        command.requestId,
        recordingFault[0] ? recordingFault : "recording_failed");
    return;
  }
  if (recordingMachine.state() == activity_state::RecordingState::Idle) {
    data_logger::LogFileInfo last = {};
    char response[160] = {0};
    if (data_logger::getLastCompletedSession(last)) {
      char crcText[9] = {0};
      crc32::format(last.crc32, crcText);
      snprintf(
          response,
          sizeof(response),
          "ok,%lu,already_stopped,%s,%lu,%s",
          static_cast<unsigned long>(command.requestId),
          last.name,
          static_cast<unsigned long>(last.sizeBytes),
          crcText);
    } else {
      snprintf(
          response,
          sizeof(response),
          "ok,%lu,already_stopped,none,0,none",
          static_cast<unsigned long>(command.requestId));
    }
    sendResponse(transport, serial, response);
    return;
  }

  if (recordingMachine.state() == activity_state::RecordingState::PausedForOffload) {
    if (!hasPendingOffload || !recordingMachine.complete()) {
      copyFault("paused_state_error");
      sendError(transport, serial, command.requestId, recordingFault);
      return;
    }
    const data_logger::LogFileInfo info = pendingOffload;
    hasPendingOffload = false;
    memset(&pendingOffload, 0, sizeof(pendingOffload));
    pendingOffloadLabel[0] = '\0';
    setLed(false);
    char crcText[9] = {0};
    crc32::format(info.crc32, crcText);
    char response[160] = {0};
    snprintf(
        response,
        sizeof(response),
        "ok,%lu,recording_stopped,%s,%lu,%s",
        static_cast<unsigned long>(command.requestId),
        info.name,
        static_cast<unsigned long>(info.sizeBytes),
        crcText);
    sendResponse(transport, serial, response);
    return;
  }

  data_logger::LogFileInfo info = {};
  if (!data_logger::stopSession(info)) {
    copyFault(data_logger::lastError());
    sendError(transport, serial, command.requestId, recordingFault);
    return;
  }
  if (!recordingMachine.complete()) {
    copyFault("recording_state_error");
    sendError(transport, serial, command.requestId, recordingFault);
    return;
  }
  setLed(false);
  char crcText[9] = {0};
  crc32::format(info.crc32, crcText);
  char response[160] = {0};
  snprintf(
      response,
      sizeof(response),
      "ok,%lu,recording_stopped,%s,%lu,%s",
      static_cast<unsigned long>(command.requestId),
      info.name,
      static_cast<unsigned long>(info.sizeBytes),
      crcText);
  sendResponse(transport, serial, response);
}

void handleSerialList(Stream& serial, uint32_t requestId) {
  if (!data_logger::beginLogList()) {
    sendError(ResponseTransport::Serial, &serial, requestId, data_logger::lastError());
    return;
  }
  uint32_t count = 0;
  while (true) {
    data_logger::LogFileInfo info = {};
    const data_logger::ListResult result = data_logger::nextLogFile(info);
    if (result == data_logger::ListResult::End) {
      break;
    }
    if (result == data_logger::ListResult::Error) {
      data_logger::endLogList();
      sendError(ResponseTransport::Serial, &serial, requestId, data_logger::lastError());
      return;
    }
    char crcText[9] = {0};
    if (info.hasCrc) {
      crc32::format(info.crc32, crcText);
    }
    char response[app_config::kBleControlResponseBufferSize] = {0};
    snprintf(
        response,
        sizeof(response),
        "file,%lu,%s,%lu,%s,%s",
        static_cast<unsigned long>(requestId),
        info.name,
        static_cast<unsigned long>(info.sizeBytes),
        info.hasCrc ? crcText : "none",
        info.complete ? "complete" : "incomplete");
    serial.println(response);
    ++count;
  }
  data_logger::endLogList();
  char response[64] = {0};
  snprintf(
      response,
      sizeof(response),
      "list_end,%lu,%lu",
      static_cast<unsigned long>(requestId),
      static_cast<unsigned long>(count));
  serial.println(response);
}

void handleList(ResponseTransport transport, Stream* serial, uint32_t requestId) {
  if (ble_service::isFileOperationActive()) {
    sendError(transport, serial, requestId, "file_operation_busy");
    return;
  }
  if (transport == ResponseTransport::Serial) {
    handleSerialList(*serial, requestId);
    return;
  }
  if (!ble_service::startLogList(requestId)) {
    sendError(transport, serial, requestId, ble_service::lastError());
  }
}

void handleDownload(
    ResponseTransport transport,
    Stream* serial,
    const protocol_v3::Command& command) {
  if (transport == ResponseTransport::Serial) {
    sendError(transport, serial, command.requestId, "ble_required");
    return;
  }
  if (!ble_service::startFileTransfer(command.requestId, command.name, command.offset)) {
    sendError(transport, serial, command.requestId, ble_service::lastError());
  }
}

void handleDelete(
    ResponseTransport transport,
    Stream* serial,
    const protocol_v3::Command& command) {
  if (ble_service::isFileOperationActive()) {
    sendError(transport, serial, command.requestId, "file_operation_busy");
    return;
  }
  const bool resumesPausedRecording =
      recordingMachine.state() == activity_state::RecordingState::PausedForOffload &&
      hasPendingOffload && strcmp(pendingOffload.name, command.name) == 0 &&
      pendingOffload.sizeBytes == command.sizeBytes && pendingOffload.crc32 == command.crc32;
  const data_logger::DeleteResult result =
      data_logger::deleteLogFile(command.name, command.sizeBytes, command.crc32);
  if (result == data_logger::DeleteResult::Error) {
    sendError(transport, serial, command.requestId, data_logger::lastError());
    return;
  }
  if (resumesPausedRecording) {
    const char* resumeError = nullptr;
    if (!refreshCachedFreeBytes()) {
      resumeError = data_logger::lastError();
    } else if (cachedFreeBytes <= app_config::kCriticalFreeBytes) {
      resumeError = "storage_critical";
    } else if (!data_logger::startSession(pendingOffloadLabel)) {
      resumeError = data_logger::lastError();
    } else if (!recordingMachine.resumeAfterOffload()) {
      resumeError = "recording_resume_failed";
    } else {
      recordingFreeBytesAtStart = cachedFreeBytes;
    }
    if (resumeError != nullptr) {
      copyFault(resumeError);
      sendError(transport, serial, command.requestId, recordingFault);
      return;
    }
    hasPendingOffload = false;
    memset(&pendingOffload, 0, sizeof(pendingOffload));
    pendingOffloadLabel[0] = '\0';
    nextSampleMs = millis();
  }
  char response[128] = {0};
  snprintf(
      response,
      sizeof(response),
      "ok,%lu,%s,%s",
      static_cast<unsigned long>(command.requestId),
      result == data_logger::DeleteResult::Deleted ? "deleted" : "already_deleted",
      command.name);
  sendResponse(transport, serial, response);
}

void dispatchCommand(
    ResponseTransport transport,
    Stream* serial,
    const protocol_v3::Command& command) {
  if (recordingMachine.state() == activity_state::RecordingState::Recording &&
      command.type != protocol_v3::CommandType::Hello &&
      command.type != protocol_v3::CommandType::Status &&
      command.type != protocol_v3::CommandType::RecordStart &&
      command.type != protocol_v3::CommandType::RecordStop) {
    sendError(transport, serial, command.requestId, "busy_recording");
    return;
  }
  if (recordingMachine.state() == activity_state::RecordingState::Fault &&
      command.type != protocol_v3::CommandType::Hello &&
      command.type != protocol_v3::CommandType::Status &&
      command.type != protocol_v3::CommandType::RecordStop) {
    sendError(transport, serial, command.requestId, "fault_requires_restart");
    return;
  }

  switch (command.type) {
    case protocol_v3::CommandType::Hello:
      sendHello(transport, serial, command.requestId);
      break;
    case protocol_v3::CommandType::Status:
      sendStatus(transport, serial, command.requestId);
      break;
    case protocol_v3::CommandType::RecordStart:
      handleRecordStart(transport, serial, command);
      break;
    case protocol_v3::CommandType::RecordStop:
      handleRecordStop(transport, serial, command);
      break;
    case protocol_v3::CommandType::List:
      handleList(transport, serial, command.requestId);
      break;
    case protocol_v3::CommandType::Download:
      handleDownload(transport, serial, command);
      break;
    case protocol_v3::CommandType::Cancel:
      ble_service::cancelFileOperation();
      {
        char response[64] = {0};
        snprintf(
            response,
            sizeof(response),
            "ok,%lu,cancelled",
            static_cast<unsigned long>(command.requestId));
        sendResponse(transport, serial, response);
      }
      break;
    case protocol_v3::CommandType::Delete:
      handleDelete(transport, serial, command);
      break;
    case protocol_v3::CommandType::Invalid:
      sendError(transport, serial, command.requestId, "invalid_command");
      break;
  }
}

void parseAndDispatch(ResponseTransport transport, Stream* serial, const char* line) {
  protocol_v3::Command command = {};
  const protocol_v3::ParseResult result = protocol_v3::parseCommand(line, command);
  if (!result.ok) {
    sendError(transport, serial, result.requestId, result.errorCode);
    return;
  }
  dispatchCommand(transport, serial, command);
}

void serviceBleCommands() {
  char command[app_config::kBleCommandBufferSize] = {0};
  if (ble_service::takeCommand(command, sizeof(command))) {
    parseAndDispatch(ResponseTransport::Ble, nullptr, command);
  }
}

void printHelp(Stream& serial) {
  serial.println("BLE protocol v3 commands are also accepted over serial:");
  serial.println("hello,<id> | status,<id> | record_start,<id>,<label> | record_stop,<id>");
  serial.println("list,<id> | delete,<id>,<name>,<size>,<CRC32> | cancel,<id>");
  serial.println("diagnostics: help | stream on | stream off");
}

void handleSerialCommand(char* command, Stream& serial) {
  if (!command || command[0] == '\0') {
    return;
  }
  if (strcmp(command, "help") == 0) {
    printHelp(serial);
    return;
  }
  if (strcmp(command, "stream on") == 0) {
    serialStreaming = true;
    serial.println("ok,stream,on");
    return;
  }
  if (strcmp(command, "stream off") == 0) {
    serialStreaming = false;
    serial.println("ok,stream,off");
    return;
  }
  parseAndDispatch(ResponseTransport::Serial, &serial, command);
}

void reportRecordingFaultIfNeeded() {
  if (recordingMachine.state() != activity_state::RecordingState::Fault) {
    return;
  }
  if (!serialFaultReported && Serial) {
    Serial.print("error,0,");
    Serial.println(recordingFault);
    serialFaultReported = true;
  }

  const bool connected = ble_service::isConnected();
  if (!connected) {
    // A future connection must receive the fault even if it was already queued
    // successfully for an earlier peer.
    faultBleWasConnected = false;
    bleFaultReported = false;
    return;
  }
  if (!faultBleWasConnected) {
    faultBleWasConnected = true;
    bleFaultReported = false;
  }
  if (!bleFaultReported && !ble_service::hasPendingControlResponse()) {
    char response[128] = {0};
    snprintf(response, sizeof(response), "error,0,%s", recordingFault);
    if (ble_service::sendControlResponse(response)) {
      bleFaultReported = true;
    }
  }
}
}

namespace app {
void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
  setLed(false);
  recordingMachine.reset();
  recordingFault[0] = '\0';

  Serial.begin(app_config::kSerialBaud);
  waitForSerial(1200);
  battery_reader::begin();
  if (!imu_reader::begin()) {
    handleFatalError("error,imu_init_failed");
  }
  if (!data_logger::begin()) {
    handleFatalError("error,storage_mount_failed");
  }

  const bool bleReady = ble_service::begin();
  resetSamplingRuntime();
  refreshCachedFreeBytes();
  if (Serial) {
    if (bleReady) {
      Serial.print("info,ble_advertising,");
      Serial.println(app_config::kBleDeviceName);
    } else {
      Serial.println("warn,ble_init_failed");
    }
    Serial.println("info,recording,idle");
    printHelp(Serial);
  }
}

void loop() {
  if (Serial) {
    serial_console::service(Serial, handleSerialCommand);
  }
  ble_service::service();
  serviceBleCommands();

  if (recordingMachine.state() != activity_state::RecordingState::Recording) {
    reportRecordingFaultIfNeeded();
    delay(10);
    return;
  }

  const uint32_t now = millis();
  if (static_cast<int32_t>(now - nextSampleMs) < 0) {
    return;
  }
  nextSampleMs = now + app_config::kSampleIntervalMs;
  const IMUSample sample = imu_reader::readSample(sampleId++, now);
  if (!data_logger::writeSample(sample, data_logger::currentLabel(), serialStreaming, Serial)) {
    copyFault(data_logger::lastError());
    reportRecordingFaultIfNeeded();
    return;
  }
  if ((sample.sampleId + 1) % app_config::kLedPulseEverySamples == 0) {
    setLed(true);
    delay(app_config::kLedPulseDurationMs);
    setLed(false);
  }
  if (!data_logger::flushIfNeeded()) {
    copyFault(data_logger::lastError());
    reportRecordingFaultIfNeeded();
    return;
  }

  const uint32_t currentBytes = data_logger::currentBytesWritten();
  const uint32_t safeBytesForSegment =
      recordingFreeBytesAtStart > app_config::kCriticalFreeBytes
          ? recordingFreeBytesAtStart - app_config::kCriticalFreeBytes
          : 0;
  if (activity_state::shouldRotateSegment(currentBytes, app_config::kSegmentMaxBytes) ||
      activity_state::shouldRotateSegment(currentBytes, safeBytesForSegment)) {
    strncpy(
        pendingOffloadLabel,
        data_logger::currentLabel(),
        sizeof(pendingOffloadLabel) - 1);
    pendingOffloadLabel[sizeof(pendingOffloadLabel) - 1] = '\0';
    data_logger::LogFileInfo completed = {};
    if (!data_logger::stopSession(completed)) {
      copyFault(data_logger::lastError());
      reportRecordingFaultIfNeeded();
      return;
    }

    if (!recordingMachine.pauseForOffload()) {
      copyFault("recording_pause_failed");
      reportRecordingFaultIfNeeded();
      return;
    }
    pendingOffload = completed;
    hasPendingOffload = true;
    if (!refreshCachedFreeBytes()) {
      copyFault(data_logger::lastError());
      reportRecordingFaultIfNeeded();
      return;
    }
    if (Serial) {
      char crcText[9] = {0};
      crc32::format(completed.crc32, crcText);
      Serial.print("info,segment_closed,");
      Serial.print(completed.name);
      Serial.print(',');
      Serial.print(completed.sizeBytes);
      Serial.print(',');
      Serial.print(crcText);
      Serial.println(",paused_for_offload");
    }
  }
}
}
