#pragma once
#include <string>
#include <cstdint>
struct ArchiveInfo {
  bool valid = false;
  uint32_t files = 0, swfs = 0, compressed = 0;
  std::string error;
};
ArchiveInfo inspectArchive(const std::string& path);
