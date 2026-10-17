#include "../app/src/main/cpp/archive_reader.h"
#include <cstdio>
int main(int argc,char**argv){
  if(argc!=2)return 2;
  auto result=inspectArchive(argv[1]);
  std::printf("valid=%d files=%u SWFs=%u compressed=%u error=%s\n",
       result.valid,result.files,result.swfs,result.compressed,result.error.c_str());
  return (result.valid && result.files==3 && result.swfs==2 && result.compressed==1)?0:1;
}
