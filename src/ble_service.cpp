#include "ble_service.h"

#include <Arduino.h>
#include <bluefruit.h>
#include <string.h>

#include "app_config.h"
#include "battery_reader.h"

namespace {
constexpr char kServiceUuid[] = "7b7d0000-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kCurrentActivityUuid[] = "7b7d0001-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kBatteryUuid[] = "7b7d0002-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kSummaryUuid[] = "7b7d0003-8f7a-4f6a-9f4f-1d2c3b4a5000";
constexpr char kCommandUuid[] = "7b7d0004-8f7a-4f6a-9f4f-1d2c3b4a5000";

BLEService activityService(kServiceUuid);
BLECharacteristic currentActivityCharacteristic(kCurrentActivityUuid);
BLECharacteristic batteryCharacteristic(kBatteryUuid);
BLECharacteristic summaryCharacteristic(kSummaryUuid);
BLECharacteristic commandCharacteristic(kCommandUuid);

bool initialized = false;
volatile bool statusRequested = false;
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

void publishStatus() {
  publishActivity();
  publishBattery();
  publishSummary();
}

void commandWritten(uint16_t connectionHandle, BLECharacteristic* characteristic, uint8_t* data, uint16_t length) {
  (void)connectionHandle;
  (void)characteristic;

  char command[app_config::kBleCommandBufferSize] = {0};
  size_t commandLength = min(static_cast<size_t>(length), sizeof(command) - 1);
  memcpy(command, data, commandLength);

  while (commandLength > 0 &&
         (command[commandLength - 1] == '\r' || command[commandLength - 1] == '\n' ||
          command[commandLength - 1] == ' ')) {
    command[--commandLength] = '\0';
  }

  if (strcmp(command, "status") == 0) {
    statusRequested = true;
  }
}

void connected(uint16_t connectionHandle) {
  (void)connectionHandle;
  statusRequested = true;
}

void configureReadableNotifiableCharacteristic(BLECharacteristic& characteristic) {
  characteristic.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  characteristic.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  characteristic.setMaxLen(app_config::kBlePayloadBufferSize);
  characteristic.begin();
}

void configureGattService() {
  activityService.begin();

  configureReadableNotifiableCharacteristic(currentActivityCharacteristic);
  configureReadableNotifiableCharacteristic(batteryCharacteristic);
  configureReadableNotifiableCharacteristic(summaryCharacteristic);

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
  if (!Bluefruit.begin(1, 0)) {
    return false;
  }

  Bluefruit.autoConnLed(false);
  Bluefruit.setTxPower(4);
  Bluefruit.setName(app_config::kBleDeviceName);
  Bluefruit.Periph.setConnectCallback(connected);

  configureGattService();
  startAdvertising();

  initialized = true;
  startedAtMs = millis();
  publishStatus();
  nextTelemetryMs = startedAtMs + app_config::kBleTelemetryIntervalMs;
  nextBatteryMs = startedAtMs + app_config::kBleBatteryIntervalMs;
  return true;
}

void service() {
  if (!initialized) {
    return;
  }

  if (statusRequested) {
    statusRequested = false;
    publishStatus();
  }

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
}
