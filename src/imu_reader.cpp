#include "imu_reader.h"

#include <LSM6DS3.h>
#include <Wire.h>
#include <Arduino.h>
#include <FreeRTOS.h>
#include <queue.h>
#include <task.h>
#include <math.h>

#include "imu_sampling.h"

namespace {
constexpr float kRadToDeg = 180.0f / static_cast<float>(PI);
constexpr uint16_t kAccelRangeG = 16;
constexpr uint16_t kGyroRangeDps = 2000;
constexpr float kAccelGPerLsb = 0.000061f * (kAccelRangeG / 2.0f);
constexpr float kGyroDpsPerLsb = 0.004375f * (kGyroRangeDps / 125.0f);
constexpr uint8_t kAccelAndGyroReadyMask = 0x03;
constexpr uint8_t kStatusReadAttempts = 3;
// A 104 Hz frame should arrive every 9.62 ms. A gap above 22 ms proves that
// at least one complete raw frame could have been overwritten in the output
// registers, so the session is stopped instead of silently accepting a hole.
constexpr uint32_t kRawFrameDeadlineUs = 22000;
constexpr UBaseType_t kSampleQueueDepth = 96;
constexpr uint16_t kSampleTaskStackWords = 768;

enum class WorkerFault : uint8_t {
  None,
  ReadError,
  DeadlineMissed,
  QueueOverflow,
};

struct QueuedSample {
  imu_sampling::AveragedFrame frame;
  uint32_t timestampMs;
};

LSM6DS3 imuPrimary(I2C_MODE, 0x6A);
LSM6DS3 imuSecondary(I2C_MODE, 0x6B);
LSM6DS3* activeImu = nullptr;
uint8_t activeImuAddress = 0;
#if defined(TARGET_SEEED_XIAO_NRF52840_SENSE) || \
    defined(TARGET_SEEED_XIAO_NRF52840_SENSE_PLUS)
TwoWire& sensorWire = Wire1;
#else
TwoWire& sensorWire = Wire;
#endif
imu_sampling::PairAverager averager;
imu_sampling::TimestampSequence timestamps;
imu_reader::CaptureStats stats = {};
const char* errorText = "none";
volatile bool capturing = false;
volatile bool workerActive = false;
volatile WorkerFault workerFault = WorkerFault::None;
bool hasPreviousRawFrame = false;
uint32_t previousRawFrameAtUs = 0;
QueueHandle_t sampleQueue = nullptr;
TaskHandle_t sampleTaskHandle = nullptr;

bool writeRegister(uint8_t address, uint8_t value) {
  if (activeImu == nullptr || activeImu->writeRegister(address, value) != IMU_SUCCESS) {
    errorText = "imu_register_write_failed";
    return false;
  }
  return true;
}

bool readRegisterRegionExact(
    uint8_t registerAddress,
    uint8_t* output,
    uint8_t length,
    uint8_t attempts,
    uint32_t* retryCounter) {
  if (activeImu == nullptr || output == nullptr || length == 0 || attempts == 0) {
    return false;
  }

  for (uint8_t attempt = 0; attempt < attempts; ++attempt) {
    sensorWire.beginTransmission(activeImuAddress);
    sensorWire.write(registerAddress);
    const uint8_t addressStatus = sensorWire.endTransmission();
    uint8_t received = 0;
    uint8_t requested = 0;
    if (addressStatus == 0) {
      requested = static_cast<uint8_t>(sensorWire.requestFrom(activeImuAddress, length));
      while (sensorWire.available() && received < length) {
        output[received++] = static_cast<uint8_t>(sensorWire.read());
      }
      while (sensorWire.available()) {
        sensorWire.read();
      }
    }
    if (addressStatus == 0 && requested == length && received == length) {
      return true;
    }
    if (attempt + 1U < attempts) {
      if (retryCounter != nullptr) {
        ++(*retryCounter);
      }
      delayMicroseconds(250);
    }
  }
  return false;
}

bool configureCandidate(LSM6DS3& candidate, uint8_t address) {
  candidate.settings.accelRange = kAccelRangeG;
  candidate.settings.accelSampleRate = imu_sampling::kInputRateHz;
  candidate.settings.accelBandWidth = 100;
  candidate.settings.accelODROff = 0;
  candidate.settings.gyroRange = kGyroRangeDps;
  candidate.settings.gyroSampleRate = imu_sampling::kInputRateHz;
  candidate.settings.timestampEnabled = 0;
  candidate.settings.timestampFifoEnabled = 0;
  if (candidate.begin() != IMU_SUCCESS) {
    return false;
  }

  activeImu = &candidate;
  activeImuAddress = address;
  sensorWire.setClock(400000);
  uint8_t ctrl3 = 0;
  if (candidate.readRegister(&ctrl3, LSM6DS3_ACC_GYRO_CTRL3_C) != IMU_SUCCESS ||
      candidate.writeRegister(LSM6DS3_ACC_GYRO_CTRL3_C, ctrl3 | 0x44) != IMU_SUCCESS ||
      candidate.writeRegister(LSM6DS3_ACC_GYRO_FIFO_CTRL5, 0x00) != IMU_SUCCESS) {
    activeImu = nullptr;
    activeImuAddress = 0;
    return false;
  }
  return true;
}

bool readStatus(uint8_t& status) {
  if (!readRegisterRegionExact(
          LSM6DS3_ACC_GYRO_STATUS_REG,
          &status,
          1,
          kStatusReadAttempts,
          &stats.statusReadRetries)) {
    errorText = "imu_status_read_failed";
    return false;
  }
  return true;
}

bool readRawFrame(imu_sampling::RawFrame& raw) {
  uint8_t bytes[12] = {0};
  if (!readRegisterRegionExact(
          LSM6DS3_ACC_GYRO_OUTX_L_G,
          bytes,
          sizeof(bytes),
          1,
          nullptr)) {
    errorText = "imu_sample_read_failed";
    return false;
  }
  auto wordAt = [&bytes](uint8_t offset) {
    return static_cast<int16_t>(
        static_cast<uint16_t>(bytes[offset]) |
        (static_cast<uint16_t>(bytes[offset + 1]) << 8));
  };
  raw.gyroX = wordAt(0);
  raw.gyroY = wordAt(2);
  raw.gyroZ = wordAt(4);
  raw.accX = wordAt(6);
  raw.accY = wordAt(8);
  raw.accZ = wordAt(10);
  return true;
}

void setWorkerFault(WorkerFault fault, const char* message) {
  workerFault = fault;
  errorText = message;
  capturing = false;
}

void sampleTask(void*) {
  while (true) {
    if (!capturing) {
      workerActive = false;
      vTaskDelay(1);
      continue;
    }
    workerActive = true;

    uint8_t status = 0;
    if (!readStatus(status)) {
      setWorkerFault(WorkerFault::ReadError, "imu_status_read_failed");
      continue;
    }
    if ((status & kAccelAndGyroReadyMask) != kAccelAndGyroReadyMask) {
      vTaskDelay(1);
      continue;
    }

    const uint32_t capturedAtUs = micros();
    if (hasPreviousRawFrame) {
      const uint32_t intervalUs = capturedAtUs - previousRawFrameAtUs;
      if (intervalUs > stats.maxRawIntervalUs) {
        stats.maxRawIntervalUs = intervalUs;
      }
      if (intervalUs > kRawFrameDeadlineUs) {
        ++stats.deadlineMisses;
        setWorkerFault(WorkerFault::DeadlineMissed, "imu_sample_deadline_missed");
        continue;
      }
    }

    imu_sampling::RawFrame raw = {};
    if (!readRawFrame(raw)) {
      setWorkerFault(WorkerFault::ReadError, "imu_sample_read_failed");
      continue;
    }
    previousRawFrameAtUs = capturedAtUs;
    hasPreviousRawFrame = true;
    ++stats.rawFrames;

    imu_sampling::AveragedFrame filtered = {};
    if (!averager.push(raw, filtered)) {
      continue;
    }
    QueuedSample queued = {filtered, timestamps.next()};
    if (xQueueSend(sampleQueue, &queued, 0) != pdTRUE) {
      setWorkerFault(WorkerFault::QueueOverflow, "imu_sample_queue_overflow");
      continue;
    }
    ++stats.outputSamples;
  }
}
}

namespace imu_reader {
bool begin() {
  errorText = "imu_init_failed";
  if (!(configureCandidate(imuPrimary, 0x6A) || configureCandidate(imuSecondary, 0x6B))) {
    return false;
  }
  sampleQueue = xQueueCreate(kSampleQueueDepth, sizeof(QueuedSample));
  if (sampleQueue == nullptr ||
      xTaskCreate(
          sampleTask,
          "imu_capture",
          kSampleTaskStackWords,
          nullptr,
          TASK_PRIO_HIGH,
          &sampleTaskHandle) != pdPASS) {
    errorText = "imu_task_create_failed";
    return false;
  }
  errorText = "none";
  return true;
}

bool startCapture(uint32_t captureStartedAtMs) {
  if (activeImu == nullptr || sampleQueue == nullptr || sampleTaskHandle == nullptr) {
    errorText = "imu_not_initialized";
    return false;
  }
  capturing = false;
  const uint32_t idleWaitStartedAtMs = millis();
  while (workerActive && millis() - idleWaitStartedAtMs < 100) {
    delay(1);
  }
  if (workerActive) {
    errorText = "imu_task_stop_timeout";
    return false;
  }
  // FIFO bypass: complete gyro+accelerometer frames are read atomically from
  // the normal output registers after both data-ready bits are asserted.
  if (!writeRegister(LSM6DS3_ACC_GYRO_FIFO_CTRL5, 0x00)) {
    return false;
  }
  averager.reset();
  timestamps.reset(captureStartedAtMs);
  xQueueReset(sampleQueue);
  stats = {};
  hasPreviousRawFrame = false;
  previousRawFrameAtUs = 0;
  workerFault = WorkerFault::None;
  errorText = "none";
  capturing = true;
  return true;
}

void stopCapture() {
  capturing = false;
  const uint32_t waitStartedAtMs = millis();
  while (workerActive && millis() - waitStartedAtMs < 100) {
    delay(1);
  }
  if (activeImu != nullptr) {
    activeImu->writeRegister(LSM6DS3_ACC_GYRO_FIFO_CTRL5, 0x00);
  }
}

ReadResult readNext(uint32_t sampleId, IMUSample& sample) {
  const WorkerFault fault = workerFault;
  if (fault != WorkerFault::None) {
    return fault == WorkerFault::DeadlineMissed
               ? ReadResult::DeadlineMissed
               : ReadResult::ReadError;
  }
  if (activeImu == nullptr || sampleQueue == nullptr) {
    return ReadResult::NoData;
  }
  QueuedSample queued = {};
  if (xQueueReceive(sampleQueue, &queued, 0) != pdTRUE) {
    return ReadResult::NoData;
  }

  const imu_sampling::AveragedFrame& filtered = queued.frame;
  const float ax = filtered.accX * kAccelGPerLsb;
  const float ay = filtered.accY * kAccelGPerLsb;
  const float az = filtered.accZ * kAccelGPerLsb;
  sample = {
      sampleId,
      queued.timestampMs,
      ax,
      ay,
      az,
      filtered.gyroX * kGyroDpsPerLsb,
      filtered.gyroY * kGyroDpsPerLsb,
      filtered.gyroZ * kGyroDpsPerLsb,
      atan2f(ay, az) * kRadToDeg,
      atan2f(-ax, sqrtf(ay * ay + az * az)) * kRadToDeg,
      0.0f,
      activeImuAddress,
  };
  return ReadResult::Sample;
}

CaptureStats captureStats() {
  return stats;
}

const char* lastError() {
  return errorText;
}

uint8_t activeAddress() {
  return activeImuAddress;
}
}
