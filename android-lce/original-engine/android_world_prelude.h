#pragma once
// Portable precompiled header used ONLY when compiling original 4J source.
// Original algorithms are imported unchanged from the user's private source tree.
#include <algorithm>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>
using __int64 = long long;
using byte = unsigned char;
#define PI 3.141592654f
#define MemSect(section) ((void)0)
struct LARGE_INTEGER { long long QuadPart; };
inline bool QueryPerformanceCounter(LARGE_INTEGER* out) {
  const auto now=std::chrono::steady_clock::now().time_since_epoch();
  out->QuadPart=std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
  return true;
}
template<class T> struct arrayWithLength {
  T* data=nullptr;
  unsigned int length=0;
  arrayWithLength()=default;
  explicit arrayWithLength(unsigned int n):data(new T[n]()),length(n){}
  T& operator[](unsigned int i){return data[i];}
  T operator[](unsigned int i) const{return data[i];}
};
using doubleArray=arrayWithLength<double>;
#include "Random.h"
