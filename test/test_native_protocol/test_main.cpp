#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <unity.h>

#include "activity_state.h"
#include "crc32.h"
#include "imu_sampling.h"
#include "protocol_v3.h"

namespace {
void expect(bool condition, const char* description) {
  TEST_ASSERT_TRUE_MESSAGE(condition, description);
}

void testCrc32() {
  const char* input = "123456789";
  TEST_ASSERT_EQUAL_HEX32(
      0xCBF43926UL,
      crc32::compute(reinterpret_cast<const uint8_t*>(input), strlen(input)));

  uint32_t state = crc32::kInitialValue;
  state = crc32::update(state, reinterpret_cast<const uint8_t*>(input), 4);
  state = crc32::update(state, reinterpret_cast<const uint8_t*>(input + 4), 5);
  TEST_ASSERT_EQUAL_HEX32(0xCBF43926UL, crc32::finalize(state));

  char formatted[9] = {0};
  crc32::format(0x00ABCDEFUL, formatted);
  TEST_ASSERT_EQUAL_STRING("00ABCDEF", formatted);
}

void testLineAssembler() {
  protocol_v3::LineAssembler assembler;
  const char* first = "record_start,42,";
  const char* second = "walking\n";
  assembler.push(reinterpret_cast<const uint8_t*>(first), strlen(first));
  char line[protocol_v3::kMaxControlRecordBytes] = {0};
  expect(!assembler.takeLine(line, sizeof(line)), "partial command is not exposed");
  assembler.push(reinterpret_cast<const uint8_t*>(second), strlen(second));
  expect(assembler.takeLine(line, sizeof(line)), "newline completes fragmented command");
  TEST_ASSERT_EQUAL_STRING("record_start,42,walking", line);

  const char* crlf = "status,7\r\n";
  assembler.push(reinterpret_cast<const uint8_t*>(crlf), strlen(crlf));
  expect(assembler.takeLine(line, sizeof(line)), "CRLF command is accepted for serial diagnostics");
  TEST_ASSERT_EQUAL_STRING("status,7", line);

  uint8_t oversized[protocol_v3::kMaxControlRecordBytes + 1] = {0};
  memset(oversized, 'a', sizeof(oversized));
  assembler.push(oversized, sizeof(oversized));
  const uint8_t newline = '\n';
  assembler.push(&newline, 1);
  expect(assembler.takeOverflow(), "oversized record reports overflow at newline");
  expect(!assembler.takeLine(line, sizeof(line)), "oversized record is discarded");
}

void testAllDatasetLabels() {
  const char* labels[] = {"walking", "running", "cycling", "sitting", "lying"};
  for (const char* label : labels) {
    TEST_ASSERT_TRUE_MESSAGE(protocol_v3::isValidLabel(label), label);
    char commandText[64] = {0};
    snprintf(commandText, sizeof(commandText), "record_start,12,%s", label);
    protocol_v3::Command command = {};
    const protocol_v3::ParseResult result = protocol_v3::parseCommand(commandText, command);
    TEST_ASSERT_TRUE_MESSAGE(result.ok, label);
    TEST_ASSERT_EQUAL_UINT32(12, command.requestId);
    TEST_ASSERT_EQUAL_STRING(label, command.label);
  }
  TEST_ASSERT_FALSE(protocol_v3::isValidLabel("jumping"));
  TEST_ASSERT_FALSE(protocol_v3::isValidLabel("Walking"));
  TEST_ASSERT_FALSE(protocol_v3::isValidLabel(""));
  TEST_ASSERT_FALSE(protocol_v3::isValidLabel(nullptr));
}

void testParserAndRequestIdBoundaries() {
  protocol_v3::Command command = {};
  protocol_v3::ParseResult result = protocol_v3::parseCommand("hello,0", command);
  expect(!result.ok && result.requestId == 0 && strcmp(result.errorCode, "invalid_request_id") == 0,
         "zero request ID is reserved for asynchronous firmware events");

  result = protocol_v3::parseCommand("hello,1", command);
  expect(result.ok && command.type == protocol_v3::CommandType::Hello && command.requestId == 1,
         "minimum client request ID parses");

  result = protocol_v3::parseCommand("status,4294967295", command);
  expect(result.ok && command.requestId == UINT32_MAX, "maximum request ID parses");

  result = protocol_v3::parseCommand("status,4294967296", command);
  expect(!result.ok && strcmp(result.errorCode, "invalid_request_id") == 0,
         "overflowing request ID is rejected");

  result = protocol_v3::parseCommand("status,-1", command);
  expect(!result.ok && strcmp(result.errorCode, "invalid_request_id") == 0,
         "negative request ID is rejected");

  result = protocol_v3::parseCommand("record_start,2,jumping", command);
  expect(!result.ok && result.requestId == 2 && strcmp(result.errorCode, "invalid_label") == 0,
         "parse errors preserve a valid request ID");

  result = protocol_v3::parseCommand("download,3,walking_12.csv,4294967295", command);
  expect(result.ok && command.offset == UINT32_MAX, "maximum uint32 offset parses");

  result = protocol_v3::parseCommand("download,3,walking_12.csv,4294967296", command);
  expect(!result.ok && strcmp(result.errorCode, "invalid_offset") == 0, "overflowing offset is rejected");

  result = protocol_v3::parseCommand("delete,4,running_9.csv,123,89ABCDEF", command);
  expect(result.ok && command.sizeBytes == 123 && command.crc32 == 0x89ABCDEFUL,
         "guarded delete parses");

  result = protocol_v3::parseCommand("delete,4,running_9.csv,123,89abcdef", command);
  expect(!result.ok && strcmp(result.errorCode, "invalid_crc32") == 0, "lowercase CRC is rejected");
}

void testManagedNames() {
  TEST_ASSERT_TRUE(protocol_v3::isManagedLogName("walking_0.csv"));
  TEST_ASSERT_TRUE(protocol_v3::isManagedLogName("fast_walking_123.csv"));
  TEST_ASSERT_TRUE(protocol_v3::isManagedLogName("lying_4294967295.csv"));
  TEST_ASSERT_FALSE(protocol_v3::isManagedLogName("../walking_0.csv"));
  TEST_ASSERT_FALSE(protocol_v3::isManagedLogName("walking/0.csv"));
  TEST_ASSERT_FALSE(protocol_v3::isManagedLogName("walking_0\\x.csv"));
  TEST_ASSERT_FALSE(protocol_v3::isManagedLogName("walking.csv"));
  TEST_ASSERT_FALSE(protocol_v3::isManagedLogName("walking_.csv"));
  TEST_ASSERT_FALSE(protocol_v3::isManagedLogName("walking_x.csv"));
  TEST_ASSERT_FALSE(protocol_v3::isManagedLogName("walking_0.CSV"));
  TEST_ASSERT_FALSE(protocol_v3::isManagedLogName(".csv"));
}

void testFileMetadataParser() {
  uint32_t sizeBytes = 0;
  uint32_t crc = 0;
  TEST_ASSERT_TRUE(protocol_v3::parseFileMetadata("0,00000000", sizeBytes, crc));
  TEST_ASSERT_EQUAL_UINT32(0, sizeBytes);
  TEST_ASSERT_EQUAL_HEX32(0, crc);

  TEST_ASSERT_TRUE(protocol_v3::parseFileMetadata("4294967295,89ABCDEF", sizeBytes, crc));
  TEST_ASSERT_EQUAL_UINT32(UINT32_MAX, sizeBytes);
  TEST_ASSERT_EQUAL_HEX32(0x89ABCDEFUL, crc);

  TEST_ASSERT_FALSE(protocol_v3::parseFileMetadata("4294967296,89ABCDEF", sizeBytes, crc));
  TEST_ASSERT_FALSE(protocol_v3::parseFileMetadata("1,89abcdef", sizeBytes, crc));
  TEST_ASSERT_FALSE(protocol_v3::parseFileMetadata("1,89ABCDEF,extra", sizeBytes, crc));
  TEST_ASSERT_FALSE(protocol_v3::parseFileMetadata(",89ABCDEF", sizeBytes, crc));
  TEST_ASSERT_FALSE(protocol_v3::parseFileMetadata("1,", sizeBytes, crc));
}

void testStateMachinesAndReplayPolicy() {
  activity_state::RecordingMachine recording;
  expect(
      activity_state::decideRecordStart(recording.state(), "", "walking") ==
          activity_state::RecordStartDecision::Begin,
      "idle start begins a session");
  expect(recording.state() == activity_state::RecordingState::Idle, "recording boots idle");
  TEST_ASSERT_TRUE(recording.begin());
  expect(recording.state() == activity_state::RecordingState::Recording, "recording starts");

  expect(
      activity_state::decideRecordStart(recording.state(), "walking", "walking") ==
          activity_state::RecordStartDecision::Replay,
      "same-label start is an idempotent replay");
  expect(
      activity_state::decideRecordStart(recording.state(), "walking", "running") ==
          activity_state::RecordStartDecision::Conflict,
      "different-label start conflicts with active recording");

  // A BLE disconnect performs no recording-state transition.
  TEST_ASSERT_FALSE(recording.begin());
  expect(recording.state() == activity_state::RecordingState::Recording, "start replay keeps one session");
  expect(recording.state() == activity_state::RecordingState::Recording, "disconnect preserves recording");

  TEST_ASSERT_TRUE(recording.pauseForOffload());
  TEST_ASSERT_FALSE(recording.pauseForOffload());
  expect(
      recording.state() == activity_state::RecordingState::PausedForOffload,
      "a finalized segment pauses sampling for verified offload");
  expect(
      activity_state::decideRecordStart(recording.state(), "walking", "walking") ==
          activity_state::RecordStartDecision::Replay,
      "same-label start remains idempotent while paused");
  expect(
      activity_state::decideRecordStart(recording.state(), "walking", "running") ==
          activity_state::RecordStartDecision::Conflict,
      "different-label start conflicts while paused");
  TEST_ASSERT_TRUE(recording.resumeAfterOffload());
  TEST_ASSERT_FALSE(recording.resumeAfterOffload());
  expect(recording.state() == activity_state::RecordingState::Recording, "verified delete resumes sampling");

  TEST_ASSERT_TRUE(recording.complete());
  TEST_ASSERT_FALSE(recording.complete());
  expect(recording.state() == activity_state::RecordingState::Idle, "stop returns idle");
  recording.fail();
  expect(recording.state() == activity_state::RecordingState::Fault, "failure enters fault");
  expect(
      activity_state::decideRecordStart(recording.state(), "walking", "walking") ==
          activity_state::RecordStartDecision::Fault,
      "fault rejects start replay");
  TEST_ASSERT_FALSE(recording.begin());

  activity_state::FileOperationMachine files;
  TEST_ASSERT_TRUE(files.begin(activity_state::FileOperation::Listing));
  TEST_ASSERT_FALSE(files.begin(activity_state::FileOperation::Downloading));
  TEST_ASSERT_TRUE(
      files.transition(activity_state::FileOperation::Listing, activity_state::FileOperation::Idle));
  TEST_ASSERT_FALSE(files.active());
}

void testSegmentRotationBoundary() {
  constexpr uint32_t limit = 1536UL * 1024UL;
  TEST_ASSERT_FALSE(activity_state::shouldRotateSegment(0, limit));
  TEST_ASSERT_FALSE(activity_state::shouldRotateSegment(limit - 1, limit));
  TEST_ASSERT_TRUE(activity_state::shouldRotateSegment(limit, limit));
  TEST_ASSERT_TRUE(activity_state::shouldRotateSegment(limit + 1, limit));
  TEST_ASSERT_FALSE(activity_state::shouldRotateSegment(UINT32_MAX, 0));
}

void testImuFifoFrameAssembly() {
  imu_sampling::FifoFrameAssembler assembler;
  assembler.reset();
  imu_sampling::RawFrame frame = {};

  TEST_ASSERT_FALSE(assembler.push(3, 999, frame));
  TEST_ASSERT_EQUAL_UINT32(1, assembler.discardedWords());
  const int16_t words[] = {10, 20, 30, 40, 50, 60};
  for (uint16_t pattern = 0; pattern < 5; ++pattern) {
    TEST_ASSERT_FALSE(assembler.push(pattern, words[pattern], frame));
  }
  TEST_ASSERT_TRUE(assembler.push(5, words[5], frame));
  TEST_ASSERT_EQUAL_INT16(10, frame.gyroX);
  TEST_ASSERT_EQUAL_INT16(20, frame.gyroY);
  TEST_ASSERT_EQUAL_INT16(30, frame.gyroZ);
  TEST_ASSERT_EQUAL_INT16(40, frame.accX);
  TEST_ASSERT_EQUAL_INT16(50, frame.accY);
  TEST_ASSERT_EQUAL_INT16(60, frame.accZ);
  TEST_ASSERT_EQUAL_UINT8(0, assembler.expectedPattern());
}

void testImuPairAveragingUsesBothInputFrames() {
  imu_sampling::PairAverager averager;
  averager.reset();
  const imu_sampling::RawFrame first = {
      INT16_MIN, -100, 100, 1000, -1000, INT16_MAX};
  const imu_sampling::RawFrame second = {
      INT16_MAX, 100, 300, 3000, 1000, INT16_MAX};
  imu_sampling::AveragedFrame output = {};
  TEST_ASSERT_FALSE(averager.push(first, output));
  TEST_ASSERT_TRUE(averager.push(second, output));
  TEST_ASSERT_FLOAT_WITHIN(0.001f, -0.5f, output.gyroX);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f, output.gyroY);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 200.0f, output.gyroZ);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 2000.0f, output.accX);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 0.0f, output.accY);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 32767.0f, output.accZ);
}

void testImuTimestampSequenceIsExactly52HzOnAverage() {
  imu_sampling::TimestampSequence timestamps;
  timestamps.reset(1000);
  uint32_t previous = 1000;
  uint32_t intervals19 = 0;
  uint32_t intervals20 = 0;
  uint32_t current = 0;
  for (uint32_t index = 0; index < imu_sampling::kOutputRateHz; ++index) {
    current = timestamps.next();
    const uint32_t interval = current - previous;
    if (interval == 19) ++intervals19;
    if (interval == 20) ++intervals20;
    TEST_ASSERT_TRUE(interval == 19 || interval == 20);
    previous = current;
  }
  TEST_ASSERT_EQUAL_UINT32(2000, current);
  TEST_ASSERT_EQUAL_UINT32(40, intervals19);
  TEST_ASSERT_EQUAL_UINT32(12, intervals20);
  TEST_ASSERT_EQUAL_UINT32(2, imu_sampling::kInputRateHz / imu_sampling::kOutputRateHz);
  TEST_ASSERT_EQUAL_UINT8(12, imu_sampling::kWordsPerOutputSample);
}
}

void setUp() {}

void tearDown() {}

int main() {
  UNITY_BEGIN();
  RUN_TEST(testCrc32);
  RUN_TEST(testLineAssembler);
  RUN_TEST(testAllDatasetLabels);
  RUN_TEST(testParserAndRequestIdBoundaries);
  RUN_TEST(testManagedNames);
  RUN_TEST(testFileMetadataParser);
  RUN_TEST(testStateMachinesAndReplayPolicy);
  RUN_TEST(testSegmentRotationBoundary);
  RUN_TEST(testImuFifoFrameAssembly);
  RUN_TEST(testImuPairAveragingUsesBothInputFrames);
  RUN_TEST(testImuTimestampSequenceIsExactly52HzOnAverage);
  return UNITY_END();
}
