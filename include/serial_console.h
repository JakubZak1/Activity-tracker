#ifndef SERIAL_CONSOLE_H
#define SERIAL_CONSOLE_H

#include <Arduino.h>

typedef void (*SerialCommandHandler)(char* command, Stream& serial);

namespace serial_console {
void service(Stream& serial, SerialCommandHandler handler);
}

#endif
