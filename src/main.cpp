#include <Arduino.h>
#include <Adafruit_TinyUSB.h>

void setup() {
  Serial.begin(115200);
  delay(2000);
  Serial.println("XIAO nRF52840 Sense dziala");
}

void loop() {
  Serial.println("tick");
  delay(1000);
}
