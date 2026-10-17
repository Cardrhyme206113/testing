#include "android_world_prelude.h"
#include "Random.h"
#include "ImprovedNoise.h"
#include "PerlinNoise.h"
#include <cstdio>
#include <cmath>
int main() {
  Random rng(0);
  if(rng.nextInt()!=-1155484576 || rng.nextInt()!=-723955400)return 1;
  Random a(987654321); ImprovedNoise n(&a);
  if(std::abs(n.getValue(1.25,2.5,3.75)-0.56659981086805)>1e-10)return 2;
  Random b(987654321); PerlinNoise p(&b,4);
  if(std::abs(p.getValue(-2.25,19.5,5.75)-(-2.36452239387396))>1e-10)return 3;
  auto values=p.getRegion({},0,0,0,4,4,4,.05,.05,.05);
  double total=0;for(unsigned i=0;i<values.length;++i)total+=values[i]*(i+1);
  delete[] values.data;
  if(std::abs(total-(-2122.20276460319292))>1e-7)return 4;
  std::puts("Original 4J Random/ImprovedNoise/PerlinNoise regression: PASS");
  return 0;
}
