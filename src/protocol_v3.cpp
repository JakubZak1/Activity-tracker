#include "protocol_v3.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>

namespace {
constexpr size_t kMaxTokens = 6;

size_t splitFields(char* line, char* fields[], size_t fieldCapacity) {
  if (!line || !fields || fieldCapacity == 0) {
    return 0;
  }

  size_t count = 1;
  fields[0] = line;
  for (char* cursor = line; *cursor != '\0'; ++cursor) {
    if (*cursor != ',') {
      continue;
    }
    *cursor = '\0';
    if (count >= fieldCapacity) {
      return fieldCapacity + 1;
    }
    fields[count++] = cursor + 1;
  }
  return count;
}

bool hasEmptyField(char* const fields[], size_t count) {
  for (size_t i = 0; i < count; ++i) {
    if (!fields[i] || fields[i][0] == '\0') {
      return true;
    }
  }
  return false;
}

protocol_v3::ParseResult failure(uint32_t requestId, const char* code) {
  return {false, requestId, code};
}
}

namespace protocol_v3 {
LineAssembler::LineAssembler() {
  reset();
}

void LineAssembler::reset() {
  buffer_[0] = '\0';
  length_ = 0;
  ready_ = false;
  discarding_ = false;
  overflowPending_ = false;
}

void LineAssembler::push(const uint8_t* data, size_t length) {
  if (!data) {
    return;
  }

  for (size_t i = 0; i < length; ++i) {
    const char ch = static_cast<char>(data[i]);
    if (discarding_) {
      if (ch == '\n') {
        discarding_ = false;
        overflowPending_ = true;
      }
      continue;
    }
    if (ready_) {
      if (ch == '\n') {
        overflowPending_ = true;
      }
      continue;
    }
    if (ch == '\n') {
      if (length_ > 0 && buffer_[length_ - 1] == '\r') {
        --length_;
      }
      buffer_[length_] = '\0';
      ready_ = true;
      continue;
    }
    if (length_ + 1 >= sizeof(buffer_)) {
      length_ = 0;
      buffer_[0] = '\0';
      discarding_ = true;
      continue;
    }
    buffer_[length_++] = ch;
    buffer_[length_] = '\0';
  }
}

bool LineAssembler::takeLine(char* output, size_t outputSize) {
  if (!output || outputSize == 0 || !ready_) {
    return false;
  }
  strncpy(output, buffer_, outputSize - 1);
  output[outputSize - 1] = '\0';
  buffer_[0] = '\0';
  length_ = 0;
  ready_ = false;
  return true;
}

bool LineAssembler::takeOverflow() {
  if (!overflowPending_) {
    return false;
  }
  overflowPending_ = false;
  return true;
}

bool parseUnsigned32(const char* text, uint32_t& value) {
  if (!text || text[0] == '\0' || text[0] == '-' || text[0] == '+') {
    return false;
  }
  for (const char* cursor = text; *cursor != '\0'; ++cursor) {
    if (*cursor < '0' || *cursor > '9') {
      return false;
    }
  }
  errno = 0;
  char* end = nullptr;
  const unsigned long parsed = strtoul(text, &end, 10);
  if (errno == ERANGE || !end || *end != '\0' || parsed > 0xFFFFFFFFUL) {
    return false;
  }
  value = static_cast<uint32_t>(parsed);
  return true;
}

bool parseCrc32(const char* text, uint32_t& value) {
  if (!text || strlen(text) != 8) {
    return false;
  }
  uint32_t parsed = 0;
  for (size_t i = 0; i < 8; ++i) {
    const char ch = text[i];
    uint8_t nibble = 0;
    if (ch >= '0' && ch <= '9') {
      nibble = static_cast<uint8_t>(ch - '0');
    } else if (ch >= 'A' && ch <= 'F') {
      nibble = static_cast<uint8_t>(ch - 'A' + 10);
    } else {
      return false;
    }
    parsed = (parsed << 4U) | nibble;
  }
  value = parsed;
  return true;
}

bool parseFileMetadata(const char* text, uint32_t& sizeBytes, uint32_t& crc32Value) {
  if (!text || text[0] == '\0') {
    return false;
  }
  const char* separator = strchr(text, ',');
  if (!separator || separator == text || separator[1] == '\0' || strchr(separator + 1, ',') != nullptr) {
    return false;
  }
  const size_t sizeLength = static_cast<size_t>(separator - text);
  if (sizeLength > 10) {
    return false;
  }
  char sizeText[11] = {0};
  memcpy(sizeText, text, sizeLength);
  uint32_t parsedSize = 0;
  uint32_t parsedCrc = 0;
  if (!parseUnsigned32(sizeText, parsedSize) || !parseCrc32(separator + 1, parsedCrc)) {
    return false;
  }
  sizeBytes = parsedSize;
  crc32Value = parsedCrc;
  return true;
}

bool isValidLabel(const char* label) {
  return label &&
         (strcmp(label, "walking") == 0 || strcmp(label, "running") == 0 || strcmp(label, "cycling") == 0 ||
          strcmp(label, "sitting") == 0 || strcmp(label, "lying") == 0);
}

bool isManagedLogName(const char* name) {
  if (!name) {
    return false;
  }
  const size_t length = strlen(name);
  if (length == 0 || length > kMaxFileNameBytes || length < 7 || strcmp(name + length - 4, ".csv") != 0) {
    return false;
  }

  const size_t stemLength = length - 4;
  size_t separator = stemLength;
  for (size_t i = stemLength; i > 0; --i) {
    if (name[i - 1] == '_') {
      separator = i - 1;
      break;
    }
  }
  if (separator == 0 || separator == stemLength || separator + 1 >= stemLength) {
    return false;
  }
  for (size_t i = 0; i < separator; ++i) {
    const char ch = name[i];
    if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_')) {
      return false;
    }
  }
  for (size_t i = separator + 1; i < stemLength; ++i) {
    if (name[i] < '0' || name[i] > '9') {
      return false;
    }
  }
  return true;
}

ParseResult parseCommand(const char* line, Command& command) {
  command = {};
  command.type = CommandType::Invalid;
  if (!line || line[0] == '\0' || strlen(line) >= kMaxControlRecordBytes) {
    return failure(0, "invalid_command");
  }

  char copy[kMaxControlRecordBytes] = {0};
  strncpy(copy, line, sizeof(copy) - 1);
  char* fields[kMaxTokens] = {nullptr};
  const size_t count = splitFields(copy, fields, kMaxTokens);
  if (count < 2 || count > kMaxTokens || hasEmptyField(fields, count)) {
    return failure(0, "invalid_command");
  }
  if (!parseUnsigned32(fields[1], command.requestId) || command.requestId == 0) {
    return failure(0, "invalid_request_id");
  }

  const auto requireCount = [&](size_t expected) -> bool { return count == expected; };
  if (strcmp(fields[0], "hello") == 0) {
    command.type = CommandType::Hello;
    return requireCount(2) ? ParseResult{true, command.requestId, nullptr}
                           : failure(command.requestId, "wrong_argument_count");
  }
  if (strcmp(fields[0], "status") == 0) {
    command.type = CommandType::Status;
    return requireCount(2) ? ParseResult{true, command.requestId, nullptr}
                           : failure(command.requestId, "wrong_argument_count");
  }
  if (strcmp(fields[0], "record_stop") == 0) {
    command.type = CommandType::RecordStop;
    return requireCount(2) ? ParseResult{true, command.requestId, nullptr}
                           : failure(command.requestId, "wrong_argument_count");
  }
  if (strcmp(fields[0], "list") == 0) {
    command.type = CommandType::List;
    return requireCount(2) ? ParseResult{true, command.requestId, nullptr}
                           : failure(command.requestId, "wrong_argument_count");
  }
  if (strcmp(fields[0], "cancel") == 0) {
    command.type = CommandType::Cancel;
    return requireCount(2) ? ParseResult{true, command.requestId, nullptr}
                           : failure(command.requestId, "wrong_argument_count");
  }
  if (strcmp(fields[0], "record_start") == 0) {
    command.type = CommandType::RecordStart;
    if (!requireCount(3)) {
      return failure(command.requestId, "wrong_argument_count");
    }
    if (!isValidLabel(fields[2])) {
      return failure(command.requestId, "invalid_label");
    }
    strncpy(command.label, fields[2], sizeof(command.label) - 1);
    return {true, command.requestId, nullptr};
  }
  if (strcmp(fields[0], "download") == 0) {
    command.type = CommandType::Download;
    if (!requireCount(4)) {
      return failure(command.requestId, "wrong_argument_count");
    }
    if (!isManagedLogName(fields[2])) {
      return failure(command.requestId, "invalid_name");
    }
    if (!parseUnsigned32(fields[3], command.offset)) {
      return failure(command.requestId, "invalid_offset");
    }
    strncpy(command.name, fields[2], sizeof(command.name) - 1);
    return {true, command.requestId, nullptr};
  }
  if (strcmp(fields[0], "delete") == 0) {
    command.type = CommandType::Delete;
    if (!requireCount(5)) {
      return failure(command.requestId, "wrong_argument_count");
    }
    if (!isManagedLogName(fields[2])) {
      return failure(command.requestId, "invalid_name");
    }
    if (!parseUnsigned32(fields[3], command.sizeBytes)) {
      return failure(command.requestId, "invalid_size");
    }
    if (!parseCrc32(fields[4], command.crc32)) {
      return failure(command.requestId, "invalid_crc32");
    }
    strncpy(command.name, fields[2], sizeof(command.name) - 1);
    return {true, command.requestId, nullptr};
  }
  return failure(command.requestId, "unknown_command");
}
}
