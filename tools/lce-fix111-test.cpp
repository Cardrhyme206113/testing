#include "lce-fix111-cbuffer-pool.hpp"
#include <cassert>
#include <iostream>
int main() {
  LceCommandBufferPool pool;
  assert(pool.create(3)==0);
  assert(pool.create(2)==3);
  int hits=0;
  LceCommandBufferPool::List commands;
  commands.emplace_back([&hits]{++hits;});
  pool.publish(0,std::move(commands));
  auto snapshot=pool.get(0);
  assert(snapshot && snapshot->size()==1);
  pool.release(0,3);
  assert(!pool.get(0));
  assert(pool.create(2)==0);
  assert(pool.create(1)==2);
  (*snapshot)[0]();
  assert(hits==1);
  snapshot.reset();
  pool.release(0,5);
  assert(pool.slots()==0);
  for(int i=0;i<30000;++i) {
    int count=(i%3)+1;
    int id=pool.create(count);
    LceCommandBufferPool::List batch;
    batch.emplace_back([i]{});
    pool.publish(id,std::move(batch));
    assert(pool.size(id)==1);
    pool.release(id,count);
    assert(pool.slots()==0);
  }
  assert(pool.create(3)==0);
  assert(pool.create(4)==3);
  pool.release(0,3);
  pool.release(3,4);
  assert(pool.slots()==0);
  std::cout<<"FIX111_CBUFF_REUSE_PASS 30000 cycles, zero retained slots\n";
}
