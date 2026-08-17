#include "app.h"

#include <Arduino.h>
#include <Adafruit_TinyUSB.h>
#include <string.h>

#include "app_config.h"
#include "battery_reader.h"
#include "ble_service.h"
#include "data_logger.h"
#include "imu_reader.h"
#include "serial_console.h"

namespace {
uint32_t nextSampleMs = 0;
uint32_t sampleId = 0;
bool serialStreaming = false;
bool writeFailureReported = false;
char activityLabel[app_config::kActivityLabelBufferSize] = {0};

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
  while (!Serial && (millis() - start < timeoutMs)) {
    delay(10);
  }
}

void handleFatalError(const char* message) {
  if (Serial) {
    Serial.println(message);
  }
  blinkFatalPattern(120, 880);
}

void resetActivityLabel() {
  strncpy(activityLabel, app_config::kDefaultActivityLabel, sizeof(activityLabel) - 1);
  activityLabel[sizeof(activityLabel) - 1] = '\0';
}

void printHelp(Stream& serial) {
  serial.println("commands: help, status, battery, space, list, read <file>, label <name>, start, stop, stream on, stream off, erase");
}

void printStatus(Stream& serial) {
  serial.print("status,logging,");
  serial.print(data_logger::isLogging() ? "on" : "off");
  serial.print(",stream,");
  serial.print(serialStreaming ? "on" : "off");
  serial.print(",label,");
  serial.print(activityLabel);
  serial.print(",imu,0x");
  serial.print(imu_reader::activeAddress(), HEX);
  serial.print(",current_file,");
  serial.print(data_logger::isLogging() ? data_logger::currentLogPath() : "none");
  const BatteryStatus battery = battery_reader::readStatus();
  serial.print(",battery_mv,");
  serial.print(battery.voltageMv);
  serial.print(",battery_percent,");
  serial.print(battery.percent);
  serial.print(",ble_connected,");
  serial.print(ble_service::isConnected() ? "yes" : "no");
  serial.print(",last_error,");
  serial.println(data_logger::lastError());
}

void printBatteryStatus(Stream& serial) {
  const BatteryStatus battery = battery_reader::readStatus();
  serial.print("battery,voltage_mv,");
  serial.print(battery.voltageMv);
  serial.print(",percent,");
  serial.print(battery.percent);
  serial.print(",raw_adc,");
  serial.println(battery.rawAdc);
}

void printStorageSpace(Stream& serial) {
  uint32_t totalBytes = 0;
  uint32_t usedBytes = 0;
  uint32_t freeBytes = 0;
  if (!data_logger::getStorageStats(totalBytes, usedBytes, freeBytes)) {
    serial.print("error,space_failed,");
    serial.println(data_logger::lastError());
    return;
  }

  const float usedPercent = totalBytes > 0 ? (100.0f * static_cast<float>(usedBytes) / static_cast<float>(totalBytes)) : 0.0f;

  serial.print("space,total_bytes,");
  serial.print(totalBytes);
  serial.print(",used_bytes,");
  serial.print(usedBytes);
  serial.print(",free_bytes,");
  serial.print(freeBytes);
  serial.print(",used_percent,");
  serial.println(usedPercent, 2);
}

void resetLoggingRuntime() {
  sampleId = 0;
  nextSampleMs = millis();
  writeFailureReported = false;
  setLed(false);
}

bool startLoggingSession() {
  if (data_logger::isLogging()) {
    return true;
  }

  resetLoggingRuntime();
  return data_logger::startSession(activityLabel);
}

void stopLoggingSession() {
  data_logger::stopSession();
}

void stopLogging(Stream& serial) {
  if (!data_logger::isLogging()) {
    serial.println("ok,logging_already_stopped");
    return;
  }

  // Stop closes the currently open CSV file so it can be safely inspected later.
  stopLoggingSession();
  serial.println("ok,logging_stopped");
}

void startLogging(Stream& serial) {
  if (data_logger::isLogging()) {
    serial.print("ok,logging_already_running,");
    serial.println(data_logger::currentLogPath());
    return;
  }

  // A new start means a brand new session file and sample numbering from zero.
  if (!startLoggingSession()) {
    serial.print("error,logging_start_failed,");
    serial.println(data_logger::lastError());
    return;
  }

  serial.print("ok,logging_started,");
  serial.println(data_logger::currentLogPath());
}

bool isValidLabel(const char* label) {
  if (!label || label[0] == '\0') {
    return false;
  }

  for (size_t i = 0; label[i] != '\0'; ++i) {
    const char ch = label[i];
    if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_')) {
      return false;
    }
  }

  return true;
}

const char* updateActivityLabel(const char* label) {
  if (data_logger::isLogging()) {
    return "label_change_requires_stop";
  }

  if (!isValidLabel(label) || strlen(label) >= sizeof(activityLabel)) {
    return "invalid_label,use_lowercase_digits_underscore";
  }

  strncpy(activityLabel, label, sizeof(activityLabel) - 1);
  activityLabel[sizeof(activityLabel) - 1] = '\0';
  if (!data_logger::saveLabel(activityLabel)) {
    return data_logger::lastError();
  }
  return nullptr;
}

void setActivityLabel(const char* label, Stream& serial) {
  const char* error = updateActivityLabel(label);
  if (error) {
    serial.print("error,");
    serial.println(error);
    return;
  }

  serial.print("ok,label,");
  serial.println(activityLabel);
}

const char* protocolFileName(const char* path) {
  return path && path[0] == '/' ? path + 1 : path;
}

void sendBleError(const char* error) {
  char response[app_config::kBleControlResponseBufferSize] = {0};
  snprintf(response, sizeof(response), "error,%s", error ? error : "unknown");
  ble_service::sendControlResponse(response);
}

void sendBleStatus() {
  uint32_t totalBytes = 0;
  uint32_t usedBytes = 0;
  uint32_t freeBytes = 0;
  if (!data_logger::getStorageStats(totalBytes, usedBytes, freeBytes)) {
    sendBleError(data_logger::lastError());
    return;
  }

  char response[app_config::kBleControlResponseBufferSize] = {0};
  snprintf(
      response,
      sizeof(response),
      "status,%s,%s,%s,%lu,%lu,%lu",
      data_logger::isLogging() ? "on" : "off",
      activityLabel,
      data_logger::isLogging() ? protocolFileName(data_logger::currentLogPath()) : "none",
      static_cast<unsigned long>(totalBytes),
      static_cast<unsigned long>(usedBytes),
      static_cast<unsigned long>(freeBytes));
  ble_service::sendControlResponse(response);
  ble_service::requestTelemetry();
}

void handleBleStart() {
  if (ble_service::isFileOperationActive()) {
    sendBleError("file_operation_busy");
    return;
  }
  if (data_logger::isLogging()) {
    char response[96] = {0};
    snprintf(
        response,
        sizeof(response),
        "ok,logging_already_running,%s",
        protocolFileName(data_logger::currentLogPath()));
    ble_service::sendControlResponse(response);
    return;
  }
  if (!startLoggingSession()) {
    sendBleError(data_logger::lastError());
    return;
  }

  char response[96] = {0};
  snprintf(
      response,
      sizeof(response),
      "ok,logging_started,%s",
      protocolFileName(data_logger::currentLogPath()));
  ble_service::sendControlResponse(response);
}

void handleBleStop() {
  if (!data_logger::isLogging()) {
    ble_service::sendControlResponse("ok,logging_already_stopped");
    return;
  }

  char fileName[64] = {0};
  strncpy(fileName, protocolFileName(data_logger::currentLogPath()), sizeof(fileName) - 1);
  stopLoggingSession();

  data_logger::LogFileInfo info = {};
  if (!data_logger::getLogFileInfo(fileName, info)) {
    sendBleError(data_logger::lastError());
    return;
  }

  char response[app_config::kBleControlResponseBufferSize] = {0};
  snprintf(
      response,
      sizeof(response),
      "ok,logging_stopped,%s,%lu",
      info.name,
      static_cast<unsigned long>(info.sizeBytes));
  ble_service::sendControlResponse(response);
}

void handleBleDownload(char* arguments) {
  char* separator = strchr(arguments, ' ');
  if (!separator) {
    sendBleError("download_usage");
    return;
  }
  *separator = '\0';
  const char* fileName = arguments;
  const char* offsetText = separator + 1;
  char* end = nullptr;
  const unsigned long parsedOffset = strtoul(offsetText, &end, 10);
  if (offsetText[0] == '\0' || !end || end[0] != '\0') {
    sendBleError("invalid_offset");
    return;
  }
  if (data_logger::isLogging()) {
    sendBleError("logging_active");
    return;
  }
  if (ble_service::isFileOperationActive()) {
    sendBleError("file_operation_busy");
    return;
  }
  if (!ble_service::startFileTransfer(fileName, static_cast<uint32_t>(parsedOffset))) {
    sendBleError(data_logger::lastError());
  }
}

void handleBleDelete(const char* fileName) {
  if (ble_service::isFileOperationActive()) {
    sendBleError("file_operation_busy");
    return;
  }
  if (!data_logger::deleteLogFile(fileName)) {
    sendBleError(data_logger::lastError());
    return;
  }

  char response[96] = {0};
  snprintf(response, sizeof(response), "ok,deleted,%s", fileName);
  ble_service::sendControlResponse(response);
}

void handleBleCommand(char* command) {
  while (*command == ' ') {
    ++command;
  }

  if (strcmp(command, "status") == 0) {
    sendBleStatus();
  } else if (strcmp(command, "start") == 0) {
    handleBleStart();
  } else if (strcmp(command, "stop") == 0) {
    handleBleStop();
  } else if (strcmp(command, "list") == 0) {
    if (!ble_service::startLogList()) {
      sendBleError(ble_service::isFileOperationActive() ? "file_operation_busy" : data_logger::lastError());
    }
  } else if (strcmp(command, "cancel") == 0) {
    ble_service::cancelFileOperation();
    ble_service::sendControlResponse("ok,cancelled");
  } else if (strncmp(command, "label ", 6) == 0) {
    const char* error = updateActivityLabel(command + 6);
    if (error) {
      sendBleError(error);
      return;
    }
    char response[64] = {0};
    snprintf(response, sizeof(response), "ok,label,%s", activityLabel);
    ble_service::sendControlResponse(response);
  } else if (strncmp(command, "download ", 9) == 0) {
    handleBleDownload(command + 9);
  } else if (strncmp(command, "delete ", 7) == 0) {
    handleBleDelete(command + 7);
  } else {
    sendBleError("unknown_command");
  }
}

void serviceBleCommands() {
  char command[app_config::kBleCommandBufferSize] = {0};
  if (ble_service::takeCommand(command, sizeof(command))) {
    handleBleCommand(command);
  }
}

void eraseLogs(Stream& serial) {
  // Erase only clears managed files. Logging stays stopped until start is called explicitly.
  if (!data_logger::eraseLogs()) {
    serial.print("error,erase_failed,");
    serial.println(data_logger::lastError());
    return;
  }

  resetLoggingRuntime();
  serial.println("ok,erase_completed");
}

void handleCommand(char* command, Stream& serial) {
  while (*command == ' ') {
    ++command;
  }

  if (*command == '\0') {
    return;
  }

  // This block is the command router. Each recognized text command maps
  // to one action in the firmware.
  if (strcmp(command, "help") == 0) {
    printHelp(serial);
    return;
  }

  if (strcmp(command, "status") == 0) {
    printStatus(serial);
    return;
  }

  if (strcmp(command, "battery") == 0) {
    printBatteryStatus(serial);
    return;
  }

  // space: prints total, used and free bytes on external flash plus usage percent.
  if (strcmp(command, "space") == 0) {
    printStorageSpace(serial);
    return;
  }

  // list: prints files currently stored in the external flash filesystem.
  if (strcmp(command, "list") == 0) {
    data_logger::listFiles(serial);
    return;
  }

  // stop: stops appending samples to flash.
  if (strcmp(command, "stop") == 0) {
    stopLogging(serial);
    return;
  }

  // start: creates a new CSV session and resumes logging.
  if (strcmp(command, "start") == 0) {
    startLogging(serial);
    return;
  }

  // erase: deletes saved logs without automatically starting a new session.
  if (strcmp(command, "erase") == 0) {
    eraseLogs(serial);
    return;
  }

  // label <name>: sets the label used for the next session and CSV rows.
  if (strncmp(command, "label ", 6) == 0) {
    setActivityLabel(command + 6, serial);
    return;
  }

  // stream on/off controls whether new samples are mirrored live to USB serial.
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

  // read <file>: dumps one stored file back over serial.
  if (strncmp(command, "read ", 5) == 0) {
    const char* path = command + 5;
    if (!data_logger::readFileToStream(path, serial)) {
      serial.print("error,file_not_found,");
      serial.println(path);
      return;
    }
    serial.print("read_end,");
    serial.println(path);
    return;
  }

  serial.print("error,unknown_command,");
  serial.println(command);
}
}

namespace app {
void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
  setLed(false);
  resetActivityLabel();

  Serial.begin(app_config::kSerialBaud);
  waitForSerial(1200);
  battery_reader::begin();

  if (!imu_reader::begin()) {
    handleFatalError("error,imu_init_failed");
  }

  if (!data_logger::begin()) {
    handleFatalError("error,storage_mount_failed");
  }

  data_logger::loadSavedLabel(activityLabel, sizeof(activityLabel));
  data_logger::clearError();

  const bool bleReady = ble_service::begin();
  if (Serial) {
    if (bleReady) {
      Serial.print("info,ble_advertising,");
      Serial.println(app_config::kBleDeviceName);
    } else {
      Serial.println("warn,ble_init_failed");
    }
  }

  resetLoggingRuntime();

  if (Serial) {
    Serial.print("info,logging,waiting_for_start,label,");
    Serial.println(activityLabel);
    printHelp(Serial);
  }
}

void loop() {
  if (Serial) {
    serial_console::service(Serial, handleCommand);
  }

  ble_service::service();
  serviceBleCommands();

  // If logging is disabled, the board stays alive in service mode so you can
  // still use commands like status/list/read/erase/start to recover.
  if (!data_logger::isLogging()) {
    if (Serial && !writeFailureReported && strcmp(data_logger::lastError(), "none") != 0) {
      Serial.print("warn,logging_disabled,");
      Serial.println(data_logger::lastError());
      writeFailureReported = true;
    }
    delay(10);
    return;
  }

  const uint32_t now = millis();
  if (static_cast<int32_t>(now - nextSampleMs) < 0) {
    return;
  }

  // nextSampleMs keeps sampling on a regular schedule instead of drifting
  // because of processing time in loop().
  nextSampleMs += app_config::kSampleIntervalMs;

  const IMUSample sample = imu_reader::readSample(sampleId++, millis());
  if (!data_logger::writeSample(sample, activityLabel, serialStreaming, Serial)) {
    writeFailureReported = false;
    return;
  }

  if ((sample.sampleId + 1) % app_config::kLedPulseEverySamples == 0) {
    setLed(true);
    delay(app_config::kLedPulseDurationMs);
    setLed(false);
  }

  data_logger::flushIfNeeded();
}
}
