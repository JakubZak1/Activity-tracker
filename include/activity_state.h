#ifndef ACTIVITY_STATE_H
#define ACTIVITY_STATE_H

#include <stdint.h>

namespace activity_state {
enum class RecordingState : uint8_t {
  Idle,
  Recording,
  PausedForOffload,
  Fault,
};

enum class FileOperation : uint8_t {
  Idle,
  Listing,
  WaitingDownloadBegin,
  Downloading,
  WaitingDownloadEnd,
};

enum class RecordStartDecision : uint8_t {
  Begin,
  Replay,
  Conflict,
  Fault,
};

class RecordingMachine {
 public:
  RecordingMachine();
  void reset();
  bool begin();
  bool pauseForOffload();
  bool resumeAfterOffload();
  bool complete();
  void fail();
  RecordingState state() const;

 private:
  RecordingState state_;
};

class FileOperationMachine {
 public:
  FileOperationMachine();
  void reset();
  bool begin(FileOperation operation);
  bool transition(FileOperation expected, FileOperation next);
  FileOperation state() const;
  bool active() const;

 private:
  FileOperation state_;
};

const char* recordingStateName(RecordingState state);
RecordStartDecision decideRecordStart(
    RecordingState state,
    const char* activeLabel,
    const char* requestedLabel);
bool shouldRotateSegment(uint32_t bytesWritten, uint32_t segmentLimitBytes);
}

#endif
