#include "ble_service.h"

#include <Arduino.h>
#include <bluefruit.h>
#include <stdio.h>
#include <string.h>

#include "app_config.h"
#include "battery_reader.h"
#include "crc32.h"
#include "data_logger.h"
#include "protocol_v3.h"

namespace {
constexpr char kServiceUuid[] = "7b7d0000-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kCurrentActivityUuid[] = "7b7d0001-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kSummaryUuid[] = "7b7d0002-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kBatteryUuid[] = "7b7d0003-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kCommandUuid[] = "7b7d0004-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kControlResponseUuid[] = "7b7d0005-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kFileDataUuid[] = "7b7d0006-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr uint16_t kFileFrameHeaderSize = 8;

enum class ControlAction : uint8_t {
  None,
  BeginTransfer,
  EndTransfer,
  EndList,
};

BLEService activityService(kServiceUuid);
BLECharacteristic currentActivityCharacteristic(kCurrentActivityUuid);
BLECharacteristic summaryCharacteristic(kSummaryUuid);
BLECharacteristic batteryCharacteristic(kBatteryUuid);
BLECharacteristic commandCharacteristic(kCommandUuid);
BLECharacteristic controlResponseCharacteristic(kControlResponseUuid);
BLECharacteristic fileDataCharacteristic(kFileDataUuid);

bool initialized = false;
volatile bool telemetryRequested = false;
volatile bool disconnectRequested = false;
volatile bool connectionResetRequested = false;
volatile uint16_t connectionResetHandle = BLE_CONN_HANDLE_INVALID;
protocol_v3::LineAssembler commandAssembler;
SemaphoreHandle_t commandAssemblerMutex = nullptr;

char pendingControlResponse[app_config::kBleControlResponseBufferSize] = {0};
uint16_t pendingControlLength = 0;
uint16_t pendingControlOffset = 0;
uint32_t pendingControlStartedMs = 0;
ControlAction pendingControlAction = ControlAction::None;
volatile bool controlIndicationConfirmed = false;
volatile bool controlIndicationFailed = false;
bool controlIndicationInFlight = false;
uint16_t controlIndicationChunkLength = 0;
bool yieldFileServiceOnce = false;

activity_state::FileOperationMachine fileOperationMachine;
uint32_t fileOperationRequestId = 0;
uint32_t fileOperationLastProgressMs = 0;
uint32_t listedFileCount = 0;
char transferFileName[64] = {0};
uint32_t transferFileSize = 0;
uint32_t transferFileCrc = 0;
uint32_t transferOffset = 0;
uint8_t fileFrame[app_config::kBleFileFrameSize] = {0};
uint16_t fileFrameLength = 0;
uint16_t fileFrameDataLength = 0;
uint32_t lastRecordingTransferFrameMs = 0;
char transportError[64] = {0};

uint32_t startedAtMs = 0;
uint32_t nextTelemetryMs = 0;
uint32_t nextBatteryMs = 0;

void setTransportError(const char* error) {
  strncpy(transportError, error ? error : "unknown", sizeof(transportError) - 1);
  transportError[sizeof(transportError) - 1] = '\0';
}

void clearTransportError() {
  transportError[0] = '\0';
}

void requestConnectionReset(uint16_t connectionHandle) {
  if (connectionHandle == BLE_CONN_HANDLE_INVALID) {
    return;
  }
  connectionResetHandle = connectionHandle;
  connectionResetRequested = true;
}

void resetCommandAssembler() {
  if (!commandAssemblerMutex) {
    commandAssembler.reset();
    return;
  }
  if (xSemaphoreTake(commandAssemblerMutex, portMAX_DELAY) == pdTRUE) {
    commandAssembler.reset();
    xSemaphoreGive(commandAssemblerMutex);
  }
}

void pushCommandBytes(const uint8_t* data, size_t length) {
  if (!commandAssemblerMutex) {
    return;
  }
  if (xSemaphoreTake(commandAssemblerMutex, portMAX_DELAY) == pdTRUE) {
    commandAssembler.push(data, length);
    xSemaphoreGive(commandAssemblerMutex);
  }
}

bool takeAssembledCommand(char* output, size_t outputSize) {
  if (!commandAssemblerMutex || xSemaphoreTake(commandAssemblerMutex, portMAX_DELAY) != pdTRUE) {
    return false;
  }
  const bool available = commandAssembler.takeLine(output, outputSize);
  xSemaphoreGive(commandAssemblerMutex);
  return available;
}

bool takeCommandOverflow() {
  if (!commandAssemblerMutex || xSemaphoreTake(commandAssemblerMutex, portMAX_DELAY) != pdTRUE) {
    return false;
  }
  const bool overflow = commandAssembler.takeOverflow();
  xSemaphoreGive(commandAssemblerMutex);
  return overflow;
}

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
  publishSummary();
  publishBattery();
}

uint16_t currentAttPayloadCapacity() {
  BLEConnection* connection = Bluefruit.Connection(Bluefruit.connHandle());
  const uint16_t mtu = connection ? connection->getMtu() : BLE_GATT_ATT_MTU_DEFAULT;
  return mtu > 3 ? mtu - 3 : 0;
}

void clearControlResponse() {
  pendingControlResponse[0] = '\0';
  pendingControlLength = 0;
  pendingControlOffset = 0;
  pendingControlStartedMs = 0;
  pendingControlAction = ControlAction::None;
  controlIndicationConfirmed = false;
  controlIndicationFailed = false;
  controlIndicationInFlight = false;
  controlIndicationChunkLength = 0;
}

bool queueControlResponse(const char* response, ControlAction action = ControlAction::None) {
  clearTransportError();
  if (!response || pendingControlLength != 0 || !Bluefruit.connected() ||
      !controlResponseCharacteristic.indicateEnabled()) {
    setTransportError(
        pendingControlLength != 0
            ? "control_response_busy"
            : (!Bluefruit.connected() ? "not_connected" : "control_indications_required"));
    return false;
  }
  const int written = snprintf(pendingControlResponse, sizeof(pendingControlResponse), "%s\n", response);
  if (written <= 0 || written >= static_cast<int>(sizeof(pendingControlResponse))) {
    clearControlResponse();
    setTransportError("control_response_too_long");
    return false;
  }
  pendingControlLength = static_cast<uint16_t>(written);
  pendingControlOffset = 0;
  pendingControlStartedMs = millis();
  pendingControlAction = action;
  controlResponseCharacteristic.write(pendingControlResponse, pendingControlLength);
  return true;
}

void resetFileOperationState() {
  data_logger::endFileRead();
  data_logger::endLogList();
  fileOperationMachine.reset();
  fileOperationRequestId = 0;
  fileOperationLastProgressMs = 0;
  listedFileCount = 0;
  transferFileName[0] = '\0';
  transferFileSize = 0;
  transferFileCrc = 0;
  transferOffset = 0;
  fileFrameLength = 0;
  fileFrameDataLength = 0;
  yieldFileServiceOnce = false;
}

void finishControlAction(ControlAction action) {
  switch (action) {
    case ControlAction::BeginTransfer:
      fileOperationMachine.transition(
          activity_state::FileOperation::WaitingDownloadBegin,
          activity_state::FileOperation::Downloading);
      fileOperationLastProgressMs = millis();
      break;
    case ControlAction::EndTransfer:
    case ControlAction::EndList:
      resetFileOperationState();
      break;
    case ControlAction::None:
      break;
  }
}

void flushControlResponse() {
  if (pendingControlLength == 0 || !Bluefruit.connected() || !controlResponseCharacteristic.indicateEnabled()) {
    return;
  }

  if (controlIndicationFailed) {
    if (Serial) {
      Serial.println("info,ble_indication,failed");
    }
    controlIndicationFailed = false;
    controlIndicationInFlight = false;
    controlIndicationChunkLength = 0;
  }
  if (controlIndicationConfirmed) {
    if (Serial) {
      Serial.println("info,ble_indication,confirmed");
    }
    controlIndicationConfirmed = false;
    if (controlIndicationInFlight) {
      pendingControlOffset += controlIndicationChunkLength;
      controlIndicationInFlight = false;
      controlIndicationChunkLength = 0;
      if (fileOperationMachine.active()) {
        fileOperationLastProgressMs = millis();
      }
      if (pendingControlOffset >= pendingControlLength) {
        const ControlAction completedAction = pendingControlAction;
        clearControlResponse();
        finishControlAction(completedAction);
        if (fileOperationMachine.active()) {
          yieldFileServiceOnce = true;
        }
        return;
      }
    }
  }
  if (controlIndicationInFlight) {
    return;
  }

  const uint16_t capacity = currentAttPayloadCapacity();
  if (capacity == 0) {
    return;
  }
  const uint16_t remaining = pendingControlLength - pendingControlOffset;
  const uint16_t chunkLength = remaining < capacity ? remaining : capacity;
  uint16_t submittedLength = chunkLength;
  ble_gatts_hvx_params_t parameters = {
      .handle = controlResponseCharacteristic.handles().value_handle,
      .type = BLE_GATT_HVX_INDICATION,
      .offset = 0,
      .p_len = &submittedLength,
      .p_data = reinterpret_cast<uint8_t*>(pendingControlResponse + pendingControlOffset),
  };
  const uint32_t status = sd_ble_gatts_hvx(Bluefruit.connHandle(), &parameters);
  if (Serial && status == NRF_SUCCESS) {
    Serial.print("info,ble_indication,submit_status=");
    Serial.print(status);
    Serial.print(",length=");
    Serial.println(submittedLength);
  }
  if (status == NRF_ERROR_TIMEOUT) {
    requestConnectionReset(Bluefruit.connHandle());
    return;
  }
  if (status != NRF_SUCCESS || submittedLength == 0) {
    return;
  }
  controlIndicationChunkLength = submittedLength;
  controlIndicationInFlight = true;
}

void queueOperationError(uint32_t requestId, const char* error) {
  char response[128] = {0};
  snprintf(
      response,
      sizeof(response),
      "error,%lu,%s",
      static_cast<unsigned long>(requestId),
      error ? error : "operation_failed");
  queueControlResponse(response);
}

void failFileOperation(const char* error) {
  const uint32_t requestId = fileOperationRequestId;
  clearControlResponse();
  resetFileOperationState();
  queueOperationError(requestId, error);
}

void serviceLogList() {
  if (fileOperationMachine.state() != activity_state::FileOperation::Listing || pendingControlLength != 0) {
    return;
  }
  data_logger::LogFileInfo info = {};
  const data_logger::ListResult result = data_logger::nextLogFile(info);
  if (result == data_logger::ListResult::Error) {
    failFileOperation(data_logger::lastError());
    return;
  }
  if (result == data_logger::ListResult::End) {
    char response[64] = {0};
    snprintf(
        response,
        sizeof(response),
        "list_end,%lu,%lu",
        static_cast<unsigned long>(fileOperationRequestId),
        static_cast<unsigned long>(listedFileCount));
    if (!queueControlResponse(response, ControlAction::EndList)) {
      failFileOperation(transportError);
    }
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
      static_cast<unsigned long>(fileOperationRequestId),
      info.name,
      static_cast<unsigned long>(info.sizeBytes),
      info.hasCrc ? crcText : "none",
      info.complete ? "complete" : "incomplete");
  if (!queueControlResponse(response)) {
    failFileOperation(transportError);
    return;
  }
  ++listedFileCount;
}

void writeUint32Le(uint8_t* output, uint32_t value) {
  output[0] = static_cast<uint8_t>(value & 0xFFU);
  output[1] = static_cast<uint8_t>((value >> 8U) & 0xFFU);
  output[2] = static_cast<uint8_t>((value >> 16U) & 0xFFU);
  output[3] = static_cast<uint8_t>((value >> 24U) & 0xFFU);
}

void serviceFileTransfer() {
  if (fileOperationMachine.state() != activity_state::FileOperation::Downloading || pendingControlLength != 0 ||
      !Bluefruit.connected() || !fileDataCharacteristic.notifyEnabled()) {
    return;
  }
  if (data_logger::isLogging()) {
    const uint32_t now = millis();
    if (static_cast<uint32_t>(now - lastRecordingTransferFrameMs) <
        app_config::kBleTransferFrameIntervalWhileRecordingMs) {
      return;
    }
    lastRecordingTransferFrameMs = now;
  }
  const uint16_t attCapacity = currentAttPayloadCapacity();
  const uint16_t frameCapacity = attCapacity < sizeof(fileFrame) ? attCapacity : sizeof(fileFrame);
  if (frameCapacity <= kFileFrameHeaderSize) {
    failFileOperation("mtu_too_small");
    return;
  }

  if (fileFrameLength == 0) {
    const uint16_t dataCapacity = frameCapacity - kFileFrameHeaderSize;
    const int bytesRead = data_logger::readFileChunk(fileFrame + kFileFrameHeaderSize, dataCapacity);
    if (bytesRead < 0) {
      failFileOperation(data_logger::lastError());
      return;
    }
    if (bytesRead == 0) {
      data_logger::endFileRead();
      fileOperationMachine.transition(
          activity_state::FileOperation::Downloading,
          activity_state::FileOperation::WaitingDownloadEnd);
      char crcText[9] = {0};
      crc32::format(transferFileCrc, crcText);
      char response[app_config::kBleControlResponseBufferSize] = {0};
      snprintf(
          response,
          sizeof(response),
          "download_end,%lu,%s,%lu,%s",
          static_cast<unsigned long>(fileOperationRequestId),
          transferFileName,
          static_cast<unsigned long>(transferFileSize),
          crcText);
      if (!queueControlResponse(response, ControlAction::EndTransfer)) {
        failFileOperation(transportError);
      }
      return;
    }

    writeUint32Le(fileFrame, fileOperationRequestId);
    writeUint32Le(fileFrame + 4, transferOffset);
    fileFrameDataLength = static_cast<uint16_t>(bytesRead);
    fileFrameLength = kFileFrameHeaderSize + fileFrameDataLength;
  }

  if (fileDataCharacteristic.notify(fileFrame, fileFrameLength)) {
    transferOffset += fileFrameDataLength;
    fileFrameLength = 0;
    fileFrameDataLength = 0;
    fileOperationLastProgressMs = millis();
  }
}

void commandWritten(uint16_t connectionHandle, BLECharacteristic* characteristic, uint8_t* data, uint16_t length) {
  (void)characteristic;
  if (!Bluefruit.connected() || connectionHandle != Bluefruit.connHandle()) {
    return;
  }
  pushCommandBytes(data, length);
}

void bleEvent(ble_evt_t* event) {
  if (!event) {
    return;
  }
  if (event->header.evt_id == BLE_GATTS_EVT_HVC &&
      event->evt.gatts_evt.params.hvc.handle == controlResponseCharacteristic.handles().value_handle) {
    controlIndicationConfirmed = true;
    return;
  }
  if (event->header.evt_id == BLE_GATTS_EVT_TIMEOUT || event->header.evt_id == BLE_GAP_EVT_DISCONNECTED) {
    controlIndicationFailed = true;
    if (event->header.evt_id == BLE_GATTS_EVT_TIMEOUT) {
      requestConnectionReset(event->evt.gatts_evt.conn_handle);
    }
  }
}

void connected(uint16_t connectionHandle) {
  (void)connectionHandle;
  connectionResetRequested = false;
  connectionResetHandle = BLE_CONN_HANDLE_INVALID;
  resetCommandAssembler();
  clearControlResponse();
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
  configureReadableNotifiableCharacteristic(summaryCharacteristic, app_config::kBlePayloadBufferSize);
  configureReadableNotifiableCharacteristic(batteryCharacteristic, app_config::kBlePayloadBufferSize);

  controlResponseCharacteristic.setProperties(CHR_PROPS_READ | CHR_PROPS_INDICATE);
  controlResponseCharacteristic.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  controlResponseCharacteristic.setMaxLen(app_config::kBleControlResponseBufferSize);
  controlResponseCharacteristic.begin();

  fileDataCharacteristic.setProperties(CHR_PROPS_NOTIFY);
  fileDataCharacteristic.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  fileDataCharacteristic.setMaxLen(app_config::kBleFileFrameSize);
  fileDataCharacteristic.begin();

  commandCharacteristic.setProperties(CHR_PROPS_WRITE);
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

void serviceTimeouts() {
  const uint32_t now = millis();
  if (pendingControlLength != 0 && static_cast<uint32_t>(now - pendingControlStartedMs) >= app_config::kBleControlTimeoutMs) {
    if (controlIndicationInFlight) {
      // An indication cannot be cancelled in the SoftDevice. Reusing this
      // connection could associate a late HVC with a newer response.
      requestConnectionReset(Bluefruit.connHandle());
      return;
    }
    clearControlResponse();
    if (fileOperationMachine.active()) {
      resetFileOperationState();
    }
  }
  if (fileOperationMachine.active() && fileOperationLastProgressMs != 0 &&
      static_cast<uint32_t>(now - fileOperationLastProgressMs) >= app_config::kBleFileOperationTimeoutMs) {
    failFileOperation("operation_timeout");
  }
}
}

namespace ble_service {
bool begin(const char* deviceName) {
  if (!commandAssemblerMutex) {
    commandAssemblerMutex = xSemaphoreCreateMutex();
    if (!commandAssemblerMutex) {
      setTransportError("ble_command_mutex_failed");
      return false;
    }
  }
  Bluefruit.configPrphBandwidth(BANDWIDTH_MAX);
  if (!Bluefruit.begin(1, 0)) {
    setTransportError("ble_begin_failed");
    return false;
  }
  Bluefruit.autoConnLed(false);
  Bluefruit.setTxPower(4);
  Bluefruit.setName(deviceName && deviceName[0] ? deviceName : app_config::kBleDeviceName);
  Bluefruit.setEventCallback(bleEvent);
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
  if (connectionResetRequested) {
    const uint16_t handle = connectionResetHandle;
    connectionResetRequested = false;
    connectionResetHandle = BLE_CONN_HANDLE_INVALID;
    resetCommandAssembler();
    clearControlResponse();
    resetFileOperationState();
    if (Bluefruit.connected(handle)) {
      Bluefruit.disconnect(handle);
    }
    return;
  }
  if (disconnectRequested) {
    disconnectRequested = false;
    resetCommandAssembler();
    clearControlResponse();
    resetFileOperationState();
  }
  if (telemetryRequested) {
    telemetryRequested = false;
    publishTelemetry();
  }
  if (takeCommandOverflow() && pendingControlLength == 0 && Bluefruit.connected()) {
    queueControlResponse("error,0,control_record_too_long");
  }

  flushControlResponse();
  if (yieldFileServiceOnce) {
    yieldFileServiceOnce = false;
  } else {
    serviceLogList();
    serviceFileTransfer();
  }
  serviceTimeouts();

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
  if (!output || outputSize == 0 || !Bluefruit.connected() || pendingControlLength != 0) {
    return false;
  }
  return takeAssembledCommand(output, outputSize);
}

bool sendControlResponse(const char* response) {
  return queueControlResponse(response);
}

bool hasPendingControlResponse() {
  return pendingControlLength != 0;
}

void requestTelemetry() {
  telemetryRequested = true;
}

bool startLogList(uint32_t requestId) {
  clearTransportError();
  if (!Bluefruit.connected() || !controlResponseCharacteristic.indicateEnabled()) {
    setTransportError("control_indications_required");
    return false;
  }
  if (fileOperationMachine.active() || pendingControlLength != 0) {
    setTransportError("file_operation_busy");
    return false;
  }
  if (!data_logger::beginLogList()) {
    return false;
  }
  if (!fileOperationMachine.begin(activity_state::FileOperation::Listing)) {
    data_logger::endLogList();
    setTransportError("file_operation_busy");
    return false;
  }
  fileOperationRequestId = requestId;
  listedFileCount = 0;
  fileOperationLastProgressMs = millis();
  return true;
}

bool startFileTransfer(uint32_t requestId, const char* name, uint32_t offset) {
  clearTransportError();
  if (!Bluefruit.connected() || !controlResponseCharacteristic.indicateEnabled()) {
    setTransportError("control_indications_required");
    return false;
  }
  if (!fileDataCharacteristic.notifyEnabled()) {
    setTransportError("file_notifications_required");
    return false;
  }
  if (fileOperationMachine.active() || pendingControlLength != 0) {
    setTransportError("file_operation_busy");
    return false;
  }

  data_logger::LogFileInfo info = {};
  if (!data_logger::beginFileRead(name, offset, info)) {
    return false;
  }
  if (!fileOperationMachine.begin(activity_state::FileOperation::WaitingDownloadBegin)) {
    data_logger::endFileRead();
    setTransportError("file_operation_busy");
    return false;
  }

  fileOperationRequestId = requestId;
  strncpy(transferFileName, info.name, sizeof(transferFileName) - 1);
  transferFileName[sizeof(transferFileName) - 1] = '\0';
  transferFileSize = info.sizeBytes;
  transferFileCrc = info.crc32;
  transferOffset = offset;
  fileFrameLength = 0;
  fileFrameDataLength = 0;
  fileOperationLastProgressMs = millis();

  char crcText[9] = {0};
  crc32::format(transferFileCrc, crcText);
  char response[app_config::kBleControlResponseBufferSize] = {0};
  snprintf(
      response,
      sizeof(response),
      "download_begin,%lu,%s,%lu,%lu,%s",
      static_cast<unsigned long>(requestId),
      transferFileName,
      static_cast<unsigned long>(transferFileSize),
      static_cast<unsigned long>(transferOffset),
      crcText);
  if (!queueControlResponse(response, ControlAction::BeginTransfer)) {
    resetFileOperationState();
    return false;
  }
  return true;
}

void cancelFileOperation() {
  if (fileOperationMachine.active()) {
    clearControlResponse();
    resetFileOperationState();
  }
}

bool isFileOperationActive() {
  return fileOperationMachine.active();
}

activity_state::FileOperation fileOperation() {
  return fileOperationMachine.state();
}

const char* lastError() {
  return transportError[0] ? transportError : data_logger::lastError();
}
}
