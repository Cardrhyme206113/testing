#include "../app/src/main/cpp/voxel_world.h"
#include <cassert>
#include <cstdio>
#include <vector>
int main() {
    VoxelWorld w;
    assert(w.get(10,0,10)==7);
    assert(w.surfaceY(15,15)>=1);
    std::vector<VoxelVertex> a,b;
    w.buildMesh(a,b);
    assert(a.size()>2000 && a.size()%6==0);
    printf("opaque vertices=%zu water vertices=%zu\n",a.size(),b.size());
    auto old=w.get(12,21,12);
    w.set(12,21,12,5);assert(w.get(12,21,12)==5);
    assert(w.save("/tmp/lce-test-world.bin"));
    w.set(12,21,12,0);
    assert(w.load("/tmp/lce-test-world.bin"));
    assert(w.get(12,21,12)==5);
    w.set(12,21,12,old);
    for(int i=0;i<90;++i)w.tick(1.f/60.f);
    assert(w.py>1.f);
    puts("world generation, culling, saving and movement passed");
}
