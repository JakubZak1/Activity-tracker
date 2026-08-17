#include "ble_service.h"

#include <Arduino.h>
#include <bluefruit.h>
#include <stdio.h>
#include <string.h>

#include "app_config.h"
#include "battery_reader.h"
#include "data_logger.h"

namespace {
constexpr char kServiceUuid[] = "7b7d0000-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kCurrentActivityUuid[] = "7b7d0001-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kBatteryUuid[] = "7b7d0002-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kSummaryUuid[] = "7b7d0003-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kCommandUuid[] = "7b7d0004-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kControlResponseUuid[] = "7b7d0005-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kFileDataUuid[] = "7b7d0006-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr uint16_t kFileFrameHeaderSize = 4;

enum class FileOperation : uint8_t {
  Idle,
  Listing,
  WaitingDownloadBegin,
  Transferring,
  WaitingDownloadEnd,
};

enum class ControlAction : uint8_t {
  None,
  BeginTransfer,
  EndTransfer,
  EndList,
};

BLEService activityService(kServiceUuid);
BLECharacteristic currentActivityCharacteristic(kCurrentActivityUuid);
BLECharacteristic batteryCharacteristic(kBatteryUuid);
BLECharacteristic summaryCharacteristic(kSummaryUuid);
BLECharacteristic commandCharacteristic(kCommandUuid);
BLECharacteristic controlResponseCharacteristic(kControlResponseUuid);
BLECharacteristic fileDataCharacteristic(kFileDataUuid);

bool initialized = false;
volatile bool telemetryRequested = false;
volatile bool disconnectRequested = false;
volatile bool commandPending = false;
volatile bool commandOverflow = false;
char receivedCommand[app_config::kBleCommandBufferSize] = {0};

char pendingControlResponse[app_config::kBleControlResponseBufferSize] = {0};
uint16_t pendingControlLength = 0;
ControlAction pendingControlAction = ControlAction::None;

FileOperation fileOperation = FileOperation::Idle;
uint16_t listedFileCount = 0;
char transferFileName[64] = {0};
uint32_t transferFileSize = 0;
uint32_t transferOffset = 0;
uint8_t fileFrame[app_config::kBleFileFrameSize] = {0};
uint16_t fileFrameLength = 0;
uint16_t fileFrameDataLength = 0;

uint32_t startedAtMs = 0;
uint32_t nextTelemetryMs = 0;
uint32_t nextBatteryMs = 0;

void writeAndNotify(BLECharacteristic& characteristic, const char* payload) {
  characteristic.write(payload);
  if (Bluefruit.connected() && characteristic.notifyEnabled()) {
    characteristic.notify(payload);
  }
}

void publishActivity() {
  writeAndNotify(currentActivityCharacteristic, "unknown,0,0");
}

void publishBattery() {
  const BatteryStatus battery = battery_reader::readStatus();
  char payload[app_config::kBlePayloadBufferSize] = {0};
  snprintf(payload, sizeof(payload), "%u,%u", battery.voltageMv, battery.percent);
  writeAndNotify(batteryCharacteristic, payload);
}

void publishSummary() {
  const uint32_t durationSeconds = (millis() - startedAtMs) / 1000;
  char payload[app_config::kBlePayloadBufferSize] = {0};
  snprintf(payload, sizeof(payload), "%lu,unknown,0", static_cast<unsigned long>(durationSeconds));
  writeAndNotify(summaryCharacteristic, payload);
}

void publishTelemetry() {
  publishActivity();
  publishBattery();
  publishSummary();
}

bool queueControlResponse(const char* response, ControlAction action = ControlAction::None) {
  if (!response || pendingControlLength != 0) {
    return false;
  }

  const int written = snprintf(pendingControlResponse, sizeof(pendingControlResponse), "%s\n", response);
  if (written <= 0 || written >= static_cast<int>(sizeof(pendingControlResponse))) {
    return false;
  }

  pendingControlLength = static_cast<uint16_t>(written);
  pendingControlAction = action;
  return true;
}

void finishControlAction(ControlAction action) {
  switch (action) {
    case ControlAction::BeginTransfer:
      fileOperation = FileOperation::Transferring;
      break;
    case ControlAction::EndTransfer:
      fileOperation = FileOperation::Idle;
      transferFileName[0] = '\0';
      transferFileSize = 0;
      transferOffset = 0;
      break;
    case ControlAction::EndList:
      data_logger::endLogList();
      fileOperation = FileOperation::Idle;
      listedFileCount = 0;
      break;
    case ControlAction::None:
      break;
  }
}

void flushControlResponse() {
  if (pendingControlLength == 0 || !Bluefruit.connected() || !controlResponseCharacteristic.notifyEnabled()) {
    return;
  }

  controlResponseCharacteristic.write(pendingControlResponse, pendingControlLength);
  if (!controlResponseCharacteristic.notify(pendingControlResponse, pendingControlLength)) {
    return;
  }

  const ControlAction completedAction = pendingControlAction;
  pendingControlLength = 0;
  pendingControlResponse[0] = '\0';
  pendingControlAction = ControlAction::None;
  finishControlAction(completedAction);
}

void serviceLogList() {
  if (fileOperation != FileOperation::Listing || pendingControlLength != 0) {
    return;
  }

  data_logger::LogFileInfo info = {};
  if (data_logger::nextLogFile(info)) {
    char response[app_config::kBleControlResponseBufferSize] = {0};
    snprintf(
        response,
        sizeof(response),
        "file,%s,%lu,%s",
        info.name,
        static_cast<unsigned long>(info.sizeBytes),
        info.active ? "active" : "closed");
    if (queueControlResponse(response)) {
      listedFileCount++;
    }
    return;
  }

  char response[48] = {0};
  snprintf(response, sizeof(response), "list_end,%u", listedFileCount);
  queueControlResponse(response, ControlAction::EndList);
}

uint16_t currentFileFrameCapacity() {
  BLEConnection* connection = Bluefruit.Connection(Bluefruit.connHandle());
  const uint16_t mtu = connection ? connection->getMtu() : BLE_GATT_ATT_MTU_DEFAULT;
  const uint16_t notificationCapacity = mtu > 3 ? mtu - 3 : 0;
  return min(static_cast<uint16_t>(sizeof(fileFrame)), notificationCapacity);
}

void writeOffsetHeader(uint32_t offset) {
  fileFrame[0] = static_cast<uint8_t>(offset & 0xFF);
  fileFrame[1] = static_cast<uint8_t>((offset >> 8) & 0xFF);
  fileFrame[2] = static_cast<uint8_t>((offset >> 16) & 0xFF);
  fileFrame[3] = static_cast<uint8_t>((offset >> 24) & 0xFF);
}

void failTransfer(const char* error) {
  data_logger::endFileRead();
  fileFrameLength = 0;
  fileFrameDataLength = 0;
  fileOperation = FileOperation::Idle;
  char response[96] = {0};
  snprintf(response, sizeof(response), "error,%s", error ? error : "file_read_failed");
  queueControlResponse(response);
}

void serviceFileTransfer() {
  if (fileOperation != FileOperation::Transferring || pendingControlLength != 0 || !Bluefruit.connected() ||
      !fileDataCharacteristic.notifyEnabled()) {
    return;
  }

  const uint16_t frameCapacity = currentFileFrameCapacity();
  if (frameCapacity <= kFileFrameHeaderSize) {
    failTransfer("mtu_too_small");
    return;
  }

  if (fileFrameLength == 0) {
    const uint16_t dataCapacity = frameCapacity - kFileFrameHeaderSize;
    const int bytesRead = data_logger::readFileChunk(fileFrame + kFileFrameHeaderSize, dataCapacity);
    if (bytesRead < 0) {
      failTransfer(data_logger::lastError());
      return;
    }
    if (bytesRead == 0) {
      data_logger::endFileRead();
      fileOperation = FileOperation::WaitingDownloadEnd;
      char response[app_config::kBleControlResponseBufferSize] = {0};
      snprintf(
          response,
          sizeof(response),
          "download_end,%s,%lu",
          transferFileName,
          static_cast<unsigned long>(transferFileSize));
      queueControlResponse(response, ControlAction::EndTransfer);
      return;
    }

    writeOffsetHeader(transferOffset);
    fileFrameDataLength = static_cast<uint16_t>(bytesRead);
    fileFrameLength = kFileFrameHeaderSize + fileFrameDataLength;
  }

  if (fileDataCharacteristic.notify(fileFrame, fileFrameLength)) {
    transferOffset += fileFrameDataLength;
    fileFrameLength = 0;
    fileFrameDataLength = 0;
  }
}

void commandWritten(uint16_t connectionHandle, BLECharacteristic* characteristic, uint8_t* data, uint16_t length) {
  (void)connectionHandle;
  (void)characteristic;

  if (commandPending) {
    commandOverflow = true;
    return;
  }

  size_t commandLength = min(static_cast<size_t>(length), sizeof(receivedCommand) - 1);
  memcpy(receivedCommand, data, commandLength);
  receivedCommand[commandLength] = '\0';
  while (commandLength > 0 &&
         (receivedCommand[commandLength - 1] == '\r' || receivedCommand[commandLength - 1] == '\n' ||
          receivedCommand[commandLength - 1] == ' ')) {
    receivedCommand[--commandLength] = '\0';
  }
  commandPending = true;
}

void connected(uint16_t connectionHandle) {
  (void)connectionHandle;
  telemetryRequested = true;
}

void disconnected(uint16_t connectionHandle, uint8_t reason) {
  (void)connectionHandle;
  (void)reason;
  disconnectRequested = true;
}

void configureReadableNotifiableCharacteristic(BLECharacteristic& characteristic, uint16_t maxLength) {
  characteristic.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  characteristic.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  characteristic.setMaxLen(maxLength);
  characteristic.begin();
}

void configureGattService() {
  activityService.begin();

  configureReadableNotifiableCharacteristic(currentActivityCharacteristic, app_config::kBlePayloadBufferSize);
  configureReadableNotifiableCharacteristic(batteryCharacteristic, app_config::kBlePayloadBufferSize);
  configureReadableNotifiableCharacteristic(summaryCharacteristic, app_config::kBlePayloadBufferSize);
  configureReadableNotifiableCharacteristic(
      controlResponseCharacteristic,
      app_config::kBleControlResponseBufferSize);

  fileDataCharacteristic.setProperties(CHR_PROPS_NOTIFY);
  fileDataCharacteristic.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  fileDataCharacteristic.setMaxLen(app_config::kBleFileFrameSize);
  fileDataCharacteristic.begin();

  commandCharacteristic.setProperties(CHR_PROPS_WRITE | CHR_PROPS_WRITE_WO_RESP);
  commandCharacteristic.setPermission(SECMODE_NO_ACCESS, SECMODE_OPEN);
  commandCharacteristic.setMaxLen(app_config::kBleCommandBufferSize);
  commandCharacteristic.setWriteCallback(commandWritten);
  commandCharacteristic.begin();
}

void startAdvertising() {
  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addTxPower();
  Bluefruit.Advertising.addService(activityService);
  Bluefruit.ScanResponse.addName();
  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(32, 244);
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);
}
}

namespace ble_service {
bool begin() {
  Bluefruit.configPrphBandwidth(BANDWIDTH_MAX);
  if (!Bluefruit.begin(1, 0)) {
    return false;
  }

  Bluefruit.autoConnLed(false);
  Bluefruit.setTxPower(4);
  Bluefruit.setName(app_config::kBleDeviceName);
  Bluefruit.Periph.setConnectCallback(connected);
  Bluefruit.Periph.setDisconnectCallback(disconnected);

  configureGattService();
  startAdvertising();

  initialized = true;
  startedAtMs = millis();
  publishTelemetry();
  nextTelemetryMs = startedAtMs + app_config::kBleTelemetryIntervalMs;
  nextBatteryMs = startedAtMs + app_config::kBleBatteryIntervalMs;
  return true;
}

void service() {
  if (!initialized) {
    return;
  }

  if (disconnectRequested) {
    disconnectRequested = false;
    cancelFileOperation();
    pendingControlLength = 0;
    pendingControlAction = ControlAction::None;
  }

  if (commandOverflow && pendingControlLength == 0) {
    commandOverflow = false;
    queueControlResponse("error,command_busy");
  }

  if (telemetryRequested) {
    telemetryRequested = false;
    publishTelemetry();
  }

  flushControlResponse();
  serviceLogList();
  serviceFileTransfer();

  const uint32_t now = millis();
  if (static_cast<int32_t>(now - nextTelemetryMs) >= 0) {
    nextTelemetryMs = now + app_config::kBleTelemetryIntervalMs;
    publishActivity();
    publishSummary();
  }

  if (static_cast<int32_t>(now - nextBatteryMs) >= 0) {
    nextBatteryMs = now + app_config::kBleBatteryIntervalMs;
    publishBattery();
  }
}

bool isConnected() {
  return initialized && Bluefruit.connected();
}

bool takeCommand(char* output, size_t outputSize) {
  if (!output || outputSize == 0 || !commandPending || pendingControlLength != 0) {
    return false;
  }

  strncpy(output, receivedCommand, outputSize - 1);
  output[outputSize - 1] = '\0';
  receivedCommand[0] = '\0';
  commandPending = false;
  return true;
}

bool sendControlResponse(const char* response) {
  return queueControlResponse(response);
}

void requestTelemetry() {
  telemetryRequested = true;
}

bool startLogList() {
  if (fileOperation != FileOperation::Idle || pendingControlLength != 0 || !data_logger::beginLogList()) {
    return false;
  }
  listedFileCount = 0;
  fileOperation = FileOperation::Listing;
  return true;
}

bool startFileTransfer(const char* name, uint32_t offset) {
  if (fileOperation != FileOperation::Idle || pendingControlLength != 0) {
    return false;
  }

  uint32_t sizeBytes = 0;
  if (!data_logger::beginFileRead(name, offset, sizeBytes)) {
    return false;
  }

  strncpy(transferFileName, name, sizeof(transferFileName) - 1);
  transferFileName[sizeof(transferFileName) - 1] = '\0';
  transferFileSize = sizeBytes;
  transferOffset = offset;
  fileFrameLength = 0;
  fileFrameDataLength = 0;
  fileOperation = FileOperation::WaitingDownloadBegin;

  char response[app_config::kBleControlResponseBufferSize] = {0};
  snprintf(
      response,
      sizeof(response),
      "download_begin,%s,%lu,%lu",
      transferFileName,
      static_cast<unsigned long>(transferFileSize),
      static_cast<unsigned long>(transferOffset));
  if (!queueControlResponse(response, ControlAction::BeginTransfer)) {
    cancelFileOperation();
    return false;
  }
  return true;
}

void cancelFileOperation() {
  pendingControlLength = 0;
  pendingControlResponse[0] = '\0';
  pendingControlAction = ControlAction::None;
  data_logger::endFileRead();
  data_logger::endLogList();
  fileOperation = FileOperation::Idle;
  listedFileCount = 0;
  transferFileName[0] = '\0';
  transferFileSize = 0;
  transferOffset = 0;
  fileFrameLength = 0;
  fileFrameDataLength = 0;
}

bool isFileOperationActive() {
  return fileOperation != FileOperation::Idle;
}
}
