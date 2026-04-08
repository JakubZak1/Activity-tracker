#include "app.h"

#include <Arduino.h>
#include <Adafruit_TinyUSB.h>
#include <string.h>

#include "app_config.h"
#include "data_logger.h"
#include "imu_reader.h"
#include "serial_console.h"

namespace {
uint32_t nextSampleMs = 0;
uint32_t sampleId = 0;
bool serialStreaming = false;
bool writeFailureReported = false;

void setLed(bool on) {
  digitalWrite(LED_BUILTIN, on ? LED_STATE_ON : (1 - LED_STATE_ON));
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

void printHelp(Stream& serial) {
  serial.println("commands: help, status, space, list, read <file>, start, stop, stream on, stream off, erase");
}

void printStatus(Stream& serial) {
  serial.print("status,logging,");
  serial.print(data_logger::isLogging() ? "on" : "off");
  serial.print(",stream,");
  serial.print(serialStreaming ? "on" : "off");
  serial.print(",label,");
  serial.print(app_config::kActivityLabel);
  serial.print(",imu,0x");
  serial.print(imu_reader::activeAddress(), HEX);
  serial.print(",current_file,");
  serial.print(data_logger::isLogging() ? data_logger::currentLogPath() : "none");
  serial.print(",last_error,");
  serial.println(data_logger::lastError());
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

void stopLogging(Stream& serial) {
  if (!data_logger::isLogging()) {
    serial.println("ok,logging_already_stopped");
    return;
  }

  // Stop closes the currently open CSV file so it can be safely inspected later.
  data_logger::stopSession();
  serial.println("ok,logging_stopped");
}

void startLogging(Stream& serial) {
  if (data_logger::isLogging()) {
    serial.print("ok,logging_already_running,");
    serial.println(data_logger::currentLogPath());
    return;
  }

  // A new start means a brand new session file and sample numbering from zero.
  sampleId = 0;
  nextSampleMs = millis();
  writeFailureReported = false;
  if (!data_logger::startSession()) {
    serial.print("error,logging_start_failed,");
    serial.println(data_logger::lastError());
    return;
  }

  serial.print("ok,logging_started,");
  serial.println(data_logger::currentLogPath());
}

void eraseLogs(Stream& serial) {
  // Erase only clears managed files. Logging stays stopped until start is called explicitly.
  if (!data_logger::eraseLogs()) {
    serial.print("error,erase_failed,");
    serial.println(data_logger::lastError());
    return;
  }

  sampleId = 0;
  nextSampleMs = millis();
  writeFailureReported = false;
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

  Serial.begin(app_config::kSerialBaud);
  waitForSerial(1200);

  if (!imu_reader::begin()) {
    handleFatalError("error,imu_init_failed");
  }

  if (!data_logger::begin()) {
    handleFatalError("error,storage_mount_failed");
  }

  if (!data_logger::startSession()) {
    handleFatalError("error,logging_start_failed");
  }

  nextSampleMs = millis();
  sampleId = 0;
  writeFailureReported = false;

  if (Serial) {
    Serial.print("info,logging_to,");
    Serial.println(data_logger::currentLogPath());
    printHelp(Serial);
  }
}

void loop() {
  if (Serial) {
    serial_console::service(Serial, handleCommand);
  }

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
  digitalToggle(LED_BUILTIN);

  const IMUSample sample = imu_reader::readSample(sampleId++, millis());
  if (!data_logger::writeSample(sample, app_config::kActivityLabel, serialStreaming, Serial)) {
    writeFailureReported = false;
    return;
  }

  data_logger::flushIfNeeded();
}
}
