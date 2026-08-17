#ifndef BLE_SERVICE_H
#define BLE_SERVICE_H

#include <stddef.h>
#include <stdint.h>

#include "activity_state.h"

namespace ble_service {
bool begin();
void service();
bool isConnected();
bool takeCommand(char* output, size_t outputSize);
bool sendControlResponse(const char* response);
bool hasPendingControlResponse();
void requestTelemetry();
bool startLogList(uint32_t requestId);
bool startFileTransfer(uint32_t requestId, const char* name, uint32_t offset);
void cancelFileOperation();
bool isFileOperationActive();
activity_state::FileOperation fileOperation();
const char* lastError();
}

#endif
