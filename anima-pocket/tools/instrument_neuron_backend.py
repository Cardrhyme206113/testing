#!/usr/bin/env python3
"""Instrument upstream ExecuTorch v1.5.1 MediaTek runtime for verifiable calls.

The trace lives INSIDE libneuron_backend.so. Unlike backend-registration checks,
it counts actual NeuronExecution_compute invocations (no CPU-only false positive).
Use only with a matching v1.5.1 checkout.
"""
import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("Usage: instrument_neuron_backend.py /path/to/executorch")
source_path = Path(sys.argv[1]) / "backends/mediatek/runtime/NeuronBackend.cpp"
source = source_path.read_text()

anchor = '#include "api/NeuronAdapter.h"'
if source.count(anchor) != 1:
    raise SystemExit("Unexpected NeuronBackend.cpp include layout")

inject = r'''
#include <jni.h>
#include <atomic>
#include <chrono>
#include <cstdint>

namespace {
std::atomic<uint64_t> g_anima_delegate_inits{0};
std::atomic<uint64_t> g_anima_vendor_compute_calls{0};
std::atomic<uint64_t> g_anima_vendor_compute_errors{0};
std::atomic<uint64_t> g_anima_vendor_compute_ns{0};
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_card_anima_NpuPteTest_nativeNeuronTrace(JNIEnv* env, jclass) {
  jlong values[4] = {
      static_cast<jlong>(g_anima_delegate_inits.load()),
      static_cast<jlong>(g_anima_vendor_compute_calls.load()),
      static_cast<jlong>(g_anima_vendor_compute_errors.load()),
      static_cast<jlong>(g_anima_vendor_compute_ns.load())
  };
  jlongArray result = env->NewLongArray(4);
  if (result != nullptr) {
    env->SetLongArrayRegion(result, 0, 4, values);
  }
  return result;
}
'''
source = source.replace(anchor, anchor + "\n" + inject)

init_anchor = "  return res == NEURON_NO_ERROR ? delegate : nullptr;"
if source.count(init_anchor) != 1:
    raise SystemExit("Unexpected NeuronBackend::init implementation")
source = source.replace(
    init_anchor,
    """  if (res == NEURON_NO_ERROR) {
    g_anima_delegate_inits.fetch_add(1, std::memory_order_relaxed);
  }
  return res == NEURON_NO_ERROR ? delegate : nullptr;""",
)

exec_anchor = """  return mExecutor.Compute() == NEURON_NO_ERROR ? Error::Ok
                                                : Error::InvalidState;"""
if source.count(exec_anchor) != 1:
    raise SystemExit("Unexpected NeuronExecuTorchDelegate::execute implementation")
source = source.replace(exec_anchor, """  const auto anima_start = std::chrono::steady_clock::now();
  const int anima_result = mExecutor.Compute();
  const auto anima_elapsed_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
      std::chrono::steady_clock::now() - anima_start).count();
  g_anima_vendor_compute_calls.fetch_add(1, std::memory_order_relaxed);
  g_anima_vendor_compute_ns.fetch_add(
      static_cast<uint64_t>(anima_elapsed_ns), std::memory_order_relaxed);
  if (anima_result != NEURON_NO_ERROR) {
    g_anima_vendor_compute_errors.fetch_add(1, std::memory_order_relaxed);
  }
  return anima_result == NEURON_NO_ERROR ? Error::Ok : Error::InvalidState;""")

source_path.write_text(source)
print(f"Instrumented {source_path} with real NeuronExecution_compute counters")
