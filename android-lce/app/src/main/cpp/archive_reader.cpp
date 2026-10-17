#include "archive_reader.h"
#include <fstream>
#include <cctype>
#include <cstdint>
#include <algorithm>
#include <string>

namespace {
bool readU16(std::istream& stream, uint16_t& result) {
  unsigned char a[2];
  if (!stream.read((char*)a,2)) return false;
  result = (uint16_t(a[0])<<8)|uint16_t(a[1]);
  return true;
}
bool readU32(std::istream& stream, uint32_t& result) {
  unsigned char a[4];
  if (!stream.read((char*)a,4)) return false;
  result = (uint32_t(a[0])<<24)|(uint32_t(a[1])<<16)|(uint32_t(a[2])<<8)|uint32_t(a[3]);
  return true;
}
}
ArchiveInfo inspectArchive(const std::string& path) {
  ArchiveInfo result;
  std::ifstream in(path,std::ios::binary|std::ios::ate);
  if(!in) { result.error="file unreadable"; return result; }
  const std::streamoff len=in.tellg();
  if(len<4 || len>2147483648LL) { result.error="bad archive size"; return result; }
  in.seekg(0);
  uint32_t count;
  if(!readU32(in,count) || count==0 || count>100000) { result.error="bad index count";return result; }
  for(uint32_t i=0;i<count;++i) {
    uint16_t n;
    if(!readU16(in,n) || n==0 || n>8192) {result.error="bad filename length";return result;}
    std::string filename(n,'\0');
    if(!in.read(&filename[0],n)) {result.error="truncated filename";return result;}
    uint32_t offset,size;
    if(!readU32(in,offset)||!readU32(in,size)) {result.error="truncated index";return result;}
    if(uint64_t(offset)+size>uint64_t(len)) {result.error="entry out of bounds";return result;}
    if(filename[0]=='*'){result.compressed++;filename.erase(0,1);}
    std::transform(filename.begin(),filename.end(),filename.begin(),[](unsigned char c){return char(std::tolower(c));});
    if(filename.size()>4 && filename.compare(filename.size()-4,4,".swf")==0) result.swfs++;
  }
  result.files=count;
  result.valid=true;
  return result;
}
