#include "activity_state.h"

#include <string.h>

namespace activity_state {
RecordingMachine::RecordingMachine() : state_(RecordingState::Idle) {}

void RecordingMachine::reset() {
  state_ = RecordingState::Idle;
}

bool RecordingMachine::begin() {
  if (state_ != RecordingState::Idle) {
    return false;
  }
  state_ = RecordingState::Recording;
  return true;
}

bool RecordingMachine::pauseForOffload() {
  if (state_ != RecordingState::Recording) {
    return false;
  }
  state_ = RecordingState::PausedForOffload;
  return true;
}

bool RecordingMachine::resumeAfterOffload() {
  if (state_ != RecordingState::PausedForOffload) {
    return false;
  }
  state_ = RecordingState::Recording;
  return true;
}

bool RecordingMachine::complete() {
  if (state_ != RecordingState::Recording && state_ != RecordingState::PausedForOffload) {
    return false;
  }
  state_ = RecordingState::Idle;
  return true;
}

void RecordingMachine::fail() {
  state_ = RecordingState::Fault;
}

RecordingState RecordingMachine::state() const {
  return state_;
}

FileOperationMachine::FileOperationMachine() : state_(FileOperation::Idle) {}

void FileOperationMachine::reset() {
  state_ = FileOperation::Idle;
}

bool FileOperationMachine::begin(FileOperation operation) {
  if (state_ != FileOperation::Idle || operation == FileOperation::Idle) {
    return false;
  }
  state_ = operation;
  return true;
}

bool FileOperationMachine::transition(FileOperation expected, FileOperation next) {
  if (state_ != expected) {
    return false;
  }
  state_ = next;
  return true;
}

FileOperation FileOperationMachine::state() const {
  return state_;
}

bool FileOperationMachine::active() const {
  return state_ != FileOperation::Idle;
}

const char* recordingStateName(RecordingState state) {
  switch (state) {
    case RecordingState::Idle:
      return "idle";
    case RecordingState::Recording:
      return "recording";
    case RecordingState::PausedForOffload:
      return "paused";
    case RecordingState::Fault:
      return "fault";
  }
  return "fault";
}

RecordStartDecision decideRecordStart(
    RecordingState state,
    const char* activeLabel,
    const char* requestedLabel) {
  if (state == RecordingState::Fault) {
    return RecordStartDecision::Fault;
  }
  if (state == RecordingState::Idle) {
    return RecordStartDecision::Begin;
  }
  if ((state == RecordingState::Recording || state == RecordingState::PausedForOffload) &&
      activeLabel && requestedLabel && strcmp(activeLabel, requestedLabel) == 0) {
    return RecordStartDecision::Replay;
  }
  return RecordStartDecision::Conflict;
}

bool shouldRotateSegment(uint32_t bytesWritten, uint32_t segmentLimitBytes) {
  return segmentLimitBytes > 0 && bytesWritten >= segmentLimitBytes;
}
}
