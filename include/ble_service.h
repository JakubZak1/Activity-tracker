#ifndef BLE_SERVICE_H
#define BLE_SERVICE_H

#include <stddef.h>
#include <stdint.h>

namespace ble_service {
bool begin();
void service();
bool isConnected();
bool takeCommand(char* output, size_t outputSize);
bool sendControlResponse(const char* response);
void requestTelemetry();
bool startLogList();
bool startFileTransfer(const char* name, uint32_t offset);
void cancelFileOperation();
bool isFileOperationActive();
}

#endif
