#ifndef PROTOCOL_V3_H
#define PROTOCOL_V3_H

#include <stddef.h>
#include <stdint.h>

namespace protocol_v3 {
constexpr size_t kMaxControlRecordBytes = 256;
constexpr size_t kMaxLabelBytes = 8;
constexpr size_t kMaxFileNameBytes = 63;

enum class CommandType : uint8_t {
  Invalid,
  Hello,
  Status,
  RecordStart,
  RecordStop,
  List,
  Download,
  Cancel,
  Delete,
};

struct Command {
  CommandType type;
  uint32_t requestId;
  char label[kMaxLabelBytes + 1];
  char name[kMaxFileNameBytes + 1];
  uint32_t offset;
  uint32_t sizeBytes;
  uint32_t crc32;
};

struct ParseResult {
  bool ok;
  uint32_t requestId;
  const char* errorCode;
};

class LineAssembler {
 public:
  LineAssembler();
  void reset();
  void push(const uint8_t* data, size_t length);
  bool takeLine(char* output, size_t outputSize);
  bool takeOverflow();

 private:
  char buffer_[kMaxControlRecordBytes];
  size_t length_;
  bool ready_;
  bool discarding_;
  bool overflowPending_;
};

ParseResult parseCommand(const char* line, Command& command);
bool isValidLabel(const char* label);
bool isManagedLogName(const char* name);
bool parseUnsigned32(const char* text, uint32_t& value);
bool parseCrc32(const char* text, uint32_t& value);
bool parseFileMetadata(const char* text, uint32_t& sizeBytes, uint32_t& crc32Value);
}

#endif
