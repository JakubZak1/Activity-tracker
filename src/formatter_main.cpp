#include <Arduino.h>
#include <Adafruit_TinyUSB.h>
#include <Adafruit_SPIFlash.h>
#include <SdFat.h>

extern "C" {
#include "fatfs/ff.h"
#include "fatfs/diskio.h"
}

namespace {
#if defined(EXTERNAL_FLASH_USE_QSPI)
Adafruit_FlashTransport_QSPI flashTransport;
#elif defined(EXTERNAL_FLASH_USE_SPI)
Adafruit_FlashTransport_SPI flashTransport(EXTERNAL_FLASH_USE_CS, EXTERNAL_FLASH_USE_SPI);
#else
#error No external flash transport is defined for this board.
#endif

Adafruit_SPIFlash flash(&flashTransport);
FatFileSystem fatfs;
// The XIAO nRF52840 Sense board variant uses a Puya P25Q16H QSPI flash chip.
const SPIFlash_Device_t kFlashDevices[] = {P25Q16H};
const char* statusMessage = "info,booting";
unsigned long lastStatusPrintMs = 0;

bool formatExternalFlash() {
  uint8_t workBuffer[4096] = {0};
  FATFS elmchamFatfs;

  if (f_mkfs("", FM_FAT, 0, workBuffer, sizeof(workBuffer)) != FR_OK) {
    return false;
  }

  if (f_mount(&elmchamFatfs, "0:", 1) != FR_OK) {
    return false;
  }

  f_unmount("0:");
  flash.syncBlocks();
  return true;
}

void setStatus(const char* message) {
  statusMessage = message;
  Serial.println(statusMessage);
}
}

extern "C" {
DSTATUS disk_status(BYTE pdrv) {
  (void)pdrv;
  return 0;
}

DSTATUS disk_initialize(BYTE pdrv) {
  (void)pdrv;
  return 0;
}

DRESULT disk_read(BYTE pdrv, BYTE* buff, DWORD sector, UINT count) {
  (void)pdrv;
  return flash.readBlocks(sector, buff, count) ? RES_OK : RES_ERROR;
}

DRESULT disk_write(BYTE pdrv, const BYTE* buff, DWORD sector, UINT count) {
  (void)pdrv;
  return flash.writeBlocks(sector, buff, count) ? RES_OK : RES_ERROR;
}

DRESULT disk_ioctl(BYTE pdrv, BYTE cmd, void* buff) {
  (void)pdrv;

  switch (cmd) {
    case CTRL_SYNC:
      flash.syncBlocks();
      return RES_OK;
    case GET_SECTOR_COUNT:
      *((DWORD*)buff) = flash.size() / 512;
      return RES_OK;
    case GET_SECTOR_SIZE:
      *((WORD*)buff) = 512;
      return RES_OK;
    case GET_BLOCK_SIZE:
      *((DWORD*)buff) = 8;
      return RES_OK;
    default:
      return RES_PARERR;
  }
}
}

void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, HIGH);

  Serial.begin(115200);
  unsigned long waitStartMs = millis();
  while (!Serial && (millis() - waitStartMs) < 4000) {
    delay(10);
  }
  delay(300);

  Serial.println("external_flash_formatter");

  if (!flash.begin(kFlashDevices, 1)) {
    setStatus("error,flash_begin_failed");
    return;
  }

  Serial.print("flash_size_bytes,");
  Serial.println(flash.size());

  if (fatfs.begin(&flash)) {
    setStatus("ok,already_formatted");
    return;
  }

  setStatus("info,formatting_external_flash");
  if (!formatExternalFlash()) {
    setStatus("error,format_failed");
    return;
  }

  if (!fatfs.begin(&flash)) {
    setStatus("error,mount_after_format_failed");
    return;
  }

  setStatus("ok,format_completed");
}

void loop() {
  const unsigned long now = millis();
  if (now - lastStatusPrintMs >= 2000) {
    Serial.println(statusMessage);
    lastStatusPrintMs = now;
  }

  digitalToggle(LED_BUILTIN);
  delay(500);
}
