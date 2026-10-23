#!/usr/bin/env python3
"""Instrument pinned stable-diffusion.cpp for Anima Pocket debugging.

Default behavior: per-segment begin/end timings with no forced graph splitting.
Optional ANIMA_DIAG_LAYER_CUTS=1: execute the existing Anima graph-cut segments
separately to expose transformer-block progress, at the cost of synchronization.
"""
from pathlib import Path

p = Path(__file__).resolve().parent / "stable-diffusion.cpp" / "src" / "core" / "ggml_runner.cpp"
source = p.read_text(encoding="utf-8")

def swap(needle, replacement):
    global source
    occurrences = source.count(needle)
    if occurrences != 1:
        raise RuntimeError(f"Upstream instrumentation anchor mismatch ({occurrences}): {needle[:110]}")
    source = source.replace(needle, replacement, 1)

source = '#include <cstdlib>\n' + source

swap(
    "cached_plan.valid && cached_plan.has_cuts && cached_plan.segments.size() > 1 &&\n"
    "                           !fits_monolithic();",
    "cached_plan.valid && cached_plan.has_cuts && cached_plan.segments.size() > 1 &&\n"
    "                           (!fits_monolithic() || std::getenv(\"ANIMA_DIAG_LAYER_CUTS\") != nullptr);",
)
swap(
    '        LOG_DEBUG("%s executing segment %zu/%zu: %s", get_desc().c_str(),\n'
    '                  index + 1, plan.segments.size(), segment.group_name.c_str());\n'
    '        if (!execute_segment(segment_graph, n_threads)) {\n'
    '            return fail_segment("execution");\n'
    '        }',
    '        LOG_INFO("ANIMA_DIAG_BEGIN model=%s segment=%zu/%zu layer=%s",\n'
    '                 get_desc().c_str(), index + 1, plan.segments.size(), segment.group_name.c_str());\n'
    '        const int64_t anima_diag_begin_ms = ggml_time_ms();\n'
    '        LOG_DEBUG("%s executing segment %zu/%zu: %s", get_desc().c_str(),\n'
    '                  index + 1, plan.segments.size(), segment.group_name.c_str());\n'
    '        if (!execute_segment(segment_graph, n_threads)) {\n'
    '            LOG_INFO("ANIMA_DIAG_FAILURE model=%s segment=%zu/%zu layer=%s elapsed_ms=%lld",\n'
    '                     get_desc().c_str(), index + 1, plan.segments.size(), segment.group_name.c_str(),\n'
    '                     (long long)(ggml_time_ms() - anima_diag_begin_ms));\n'
    '            return fail_segment("execution");\n'
    '        }\n'
    '        LOG_INFO("ANIMA_DIAG_END model=%s segment=%zu/%zu layer=%s elapsed_ms=%lld",\n'
    '                 get_desc().c_str(), index + 1, plan.segments.size(), segment.group_name.c_str(),\n'
    '                 (long long)(ggml_time_ms() - anima_diag_begin_ms));',
)
p.write_text(source, encoding="utf-8")
print("Anima graph timers and optional transformer block checkpoints installed")
