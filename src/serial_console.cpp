#include "serial_console.h"

#include <string.h>

#include "app_config.h"

namespace {
char commandBuffer[app_config::kCommandBufferSize] = {0};
size_t commandLength = 0;
}

namespace serial_console {
void service(Stream& serial, SerialCommandHandler handler) {
  while (serial.available()) {
    const char ch = static_cast<char>(serial.read());
    if (ch == '\r') {
      continue;
    }

    if (ch == '\n') {
      // A full line has been received, so pass the complete command text
      // to the higher-level handler in app.cpp.
      commandBuffer[commandLength] = '\0';
      handler(commandBuffer, serial);
      commandLength = 0;
      commandBuffer[0] = '\0';
      continue;
    }

    // Characters are buffered until Enter is pressed in the serial monitor.
    if (commandLength + 1 < app_config::kCommandBufferSize) {
      commandBuffer[commandLength++] = ch;
      commandBuffer[commandLength] = '\0';
    }
  }
}
}
