#!/usr/bin/env python3
"""Patch the real LCE Web renderer's command-buffer lifecycle.
Usage: python tools/lce-fix111-apply.py /path/to/lce-web-work
Requires private source checkout; never upload source to a public repository.
"""
from pathlib import Path
import shutil, sys

root = Path(sys.argv[1]).resolve()
target = root/'Minecraft.Client/Web/Web_Render.cpp'
assert target.is_file(), target
source = target.read_text()
if 'LceCommandBufferPool cbuffPool;' in source:
    print('FIX111_ALREADY_APPLIED')
    sys.exit(0)
assert '#include "WebStubs.h"\n' in source
assert '    std::vector<std::vector<std::function<void()>>> cbuffs;\n    std::mutex cbuffMutex;\n' in source
start = source.index('int C4JRender::CBuffCreate(int count){')
end = source.index('void C4JRender::CBuffTick(){}', start)
replacement = '''int C4JRender::CBuffCreate(int count){return g.cbuffPool.create(count);}
void C4JRender::CBuffDelete(int first,int count){g.cbuffPool.release(first,count);}
void C4JRender::CBuffStart(int i,bool){
    if(i<0) return;
    g_cbufThread.recording=i;
    g_cbufThread.pending.clear();
}
void C4JRender::CBuffClear(int i){g.cbuffPool.clear(i);}
int C4JRender::CBuffSize(int i){return g.cbuffPool.size(i);}
void C4JRender::CBuffEnd(){
    const int rec=g_cbufThread.recording;
    if(rec<0) return;
    g_cbufThread.recording=-1;
    std::vector<std::function<void()>> finished;
    finished.swap(g_cbufThread.pending);
    g.cbuffPool.publish(rec,std::move(finished));
    ++g.diagCompiledLists;
}
bool C4JRender::CBuffCall(int i,bool){
    if(i<0) return false;
    ++g.diagCBuffCalls;
    if(g_cbufThread.recording>=0 && !g_cbufThread.replaying)
        return record([=]{ RenderManager.CBuffCall(i,true); });
    // Immutable snapshot: no expensive per-draw std::function vector copy.
    auto commands=g.cbuffPool.get(i);
    if(!commands || commands->empty()) { ++g.diagCBuffMisses; return false; }
    const bool old=g_cbufThread.replaying;
    const int oldList=g_replayList;
    g_cbufThread.replaying=true;
    g_replayList=i;
    for(const auto& f:*commands) f();
    g_replayList=oldList;
    g_cbufThread.replaying=old;
    return true;
}
'''
source = source[:start] + replacement + source[end:]
source = source.replace('#include "WebStubs.h"\n', '#include "WebStubs.h"\n#include "Web_CBuffPool.h"\n', 1)
source = source.replace('    std::vector<std::vector<std::function<void()>>> cbuffs;\n    std::mutex cbuffMutex;\n', '    LceCommandBufferPool cbuffPool;\n', 1)
assert 'g.cbuffs' not in source and 'g.cbuffMutex' not in source
header = Path(__file__).with_name('lce-fix111-cbuffer-pool.hpp')
assert header.is_file()
destination = target.with_name('Web_CBuffPool.h')
if destination.exists():
    assert destination.read_bytes() == header.read_bytes(), 'Existing pool header differs'
else:
    shutil.copyfile(header, destination)
target.write_text(source)
print('FIX111_APPLIED native CBuffCreate/Delete reuse, immutable snapshot draws')
