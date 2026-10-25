#!/usr/bin/env python3
"""Patch pinned stable-diffusion.cpp's existing Anima graph cuts to dispatch
only diffusion DiT blocks to Android's verified ExecuTorch/Neuron runtime.

The original Qwen/T5 conditioner, LLM adapter, timestep/stem/final graph,
Euler sampler and VAE stay native to stable-diffusion.cpp. This is an explicit
hybrid path; in ordinary builds the code remains byte-for-byte functionally
equivalent to upstream. Fail closed on missing real block inputs, no synthetic
conditioning and no silent CPU substitution in hybrid mode.
"""
from pathlib import Path

base = Path(__file__).resolve().parents[1] / "app/src/main/cpp/stable-diffusion.cpp"
runner = base / "src/core/ggml_runner.cpp"
cache_h = base / "src/core/runner_cache.h"
cache_cpp = base / "src/core/runner_cache.cpp"

def exactly_once(path, original, replacement):
    content = path.read_text()
    if content.count(original) != 1:
        raise RuntimeError(f"{path.name} upstream patch anchor mismatch: "
                           f"{content.count(original)} occurrences of {original[:100]!r}")
    path.write_text(content.replace(original, replacement, 1))

exactly_once(cache_h,
    "        ggml_status capture(ggml_cgraph* graph, const ggml_graph_cut::Segment& segment, const char* log_desc);",
    """        ggml_status capture(ggml_cgraph* graph, const ggml_graph_cut::Segment& segment, const char* log_desc);
        // Native NPU block output replaces the corresponding compiled GGML graph cut.
        bool store_f32(const std::string& name, const ggml_tensor* reference,
                       const float* values, size_t value_count);""")

exactly_once(cache_cpp,
    "    void GraphCutTensorCache::prune(const std::unordered_set<std::string>& keep_names) {",
    """    bool GraphCutTensorCache::store_f32(const std::string& name, const ggml_tensor* reference,
                                         const float* values, size_t value_count) {
        if (reference == nullptr || values == nullptr ||
            reference->type != GGML_TYPE_F32 ||
            ggml_nelements(reference) != static_cast<int64_t>(value_count)) {
            return false;
        }
        auto entry = std::make_unique<CachedTensor>();
        entry->context = ggml_init({2 * ggml_tensor_overhead(), nullptr, true});
        if (entry->context == nullptr) return false;
        entry->tensor = ggml_new_tensor_4d(entry->context, GGML_TYPE_F32,
            reference->ne[0], reference->ne[1], reference->ne[2], reference->ne[3]);
        if (entry->tensor == nullptr) return false;
        ggml_set_name(entry->tensor, name.c_str());
        entry->buffer = ggml_backend_alloc_ctx_tensors(entry->context, backend_);
        if (entry->buffer == nullptr) return false;
        ggml_backend_tensor_set(entry->tensor, values, 0, value_count * sizeof(float));
        tensors_[name] = std::move(entry);
        return true;
    }

    void GraphCutTensorCache::prune(const std::unordered_set<std::string>& keep_names) {""")

exactly_once(runner,
    '#include <map>\n',
    """#include <map>
#include <cstdlib>
#include <vector>

// Implemented by the Android JNI bridge in libanimabridge; linked with the
// stable-diffusion native runner. No ExecuTorch Java symbols are required here.
extern "C" bool anima_android_npu_run_block(
    int index, const float* x, int64_t xLength,
    const float* context, int64_t contextLength,
    const float* step, int64_t stepLength,
    const float* temb, int64_t tembLength,
    float* output);
""")

# Unlike the original Vulkan-only segmented path, hybrid NPU must run the
# small native prelude/final graph on CPU, while bypassing 28 block segments.
exactly_once(runner,
    'const bool segmented = !is_multi_device() && !sd_backend_is_cpu(runtime_backend) &&',
    '''const bool segmented = !is_multi_device() &&
                            (!sd_backend_is_cpu(runtime_backend) ||
                             (get_desc() == "anima" && std::getenv("ANIMA_NPU_DIT") != nullptr)) &&''')

# Force graph-cut execution even if the original GGML graph fits RAM;
# otherwise the native sampler would run monolithically and never hit Java.
exactly_once(runner,
    '(!fits_monolithic() || std::getenv("ANIMA_DIAG_LAYER_CUTS") != nullptr);',
    '(!fits_monolithic() || std::getenv("ANIMA_DIAG_LAYER_CUTS") != nullptr ||\n'
    '                            std::getenv("ANIMA_NPU_DIT") != nullptr);')

anchor = '        ggml_context* segment_context = nullptr;'
insert = """        // Real Anima A16W4 NPU execution. The previous graph-cut input buffers
        // contain genuine input embedding/text conditioning from native GGML.
        // Crucially: bypass the block's GGUF weights and its entire GGML graph.
        if (get_desc() == "anima" && std::getenv("ANIMA_NPU_DIT") != nullptr &&
            segment.group_name.rfind("anima.blocks.", 0) == 0) {
            const std::string idx_text = segment.group_name.substr(sizeof("anima.blocks.") - 1);
            char* end_idx = nullptr;
            const long index_long = std::strtol(idx_text.c_str(), &end_idx, 10);
            if (end_idx == idx_text.c_str() || *end_idx != '\\0' ||
                index_long < 0 || index_long >= 28) {
                LOG_ERROR("ANIMA_NPU_BAD_BLOCK_ID %s", segment.group_name.c_str());
                return fail_segment("invalid npu block index");
            }
            const int block_id = static_cast<int>(index_long);
            const auto cut_name = [](const std::string& group, const char* value) {
                return ggml_graph_cut::make_graph_cut_name(group, value);
            };
            const std::string prev_group = block_id == 0 ? "anima.prelude" :
                "anima.blocks." + std::to_string(block_id - 1);
            auto load_cut = [&](const std::string& name, std::vector<float>& out) -> bool {
                ggml_tensor* tensor = cut_cache_.get(name);
                if (tensor == nullptr || tensor->type != GGML_TYPE_F32 ||
                    !ggml_is_contiguous(tensor) || ggml_graph_cut::tensor_buffer(tensor) == nullptr) {
                    LOG_ERROR("ANIMA_NPU_MISSING_F32_CUT name=%s", name.c_str());
                    return false;
                }
                const size_t count = static_cast<size_t>(ggml_nelements(tensor));
                out.resize(count);
                ggml_backend_tensor_get(tensor, out.data(), 0, count * sizeof(float));
                return true;
            };
            std::vector<float> x, context, step, temb;
            ggml_backend_synchronize(runtime_backend);
            if (!load_cut(cut_name(prev_group, "x"), x) ||
                !load_cut(cut_name("anima.prelude", "context"), context) ||
                !load_cut(cut_name("anima.prelude", "embedded_timestep"), step) ||
                !load_cut(cut_name("anima.prelude", "temb"), temb)) {
                return fail_segment("npu input cache read");
            }
            if (x.size() != 1024ULL * 2048 || context.size() != 512ULL * 1024 ||
                step.size() != 2048 || temb.size() != 6144) {
                LOG_ERROR("ANIMA_NPU_INCOMPATIBLE_SHAPES block=%d x=%zu context=%zu step=%zu temb=%zu",
                          block_id, x.size(), context.size(), step.size(), temb.size());
                return fail_segment("npu input shapes");
            }
            std::vector<float> output(x.size());
            const int64_t started = ggml_time_ms();
            LOG_INFO("ANIMA_NPU_DIT_BEGIN block=%d", block_id);
            if (!anima_android_npu_run_block(block_id,
                    x.data(), static_cast<int64_t>(x.size()),
                    context.data(), static_cast<int64_t>(context.size()),
                    step.data(), static_cast<int64_t>(step.size()),
                    temb.data(), static_cast<int64_t>(temb.size()),
                    output.data())) {
                LOG_ERROR("ANIMA_NPU_DIT_FAILURE block=%d", block_id);
                return fail_segment("vendor Neuron PTE forward");
            }
            bool saved = false;
            for (int cut_index : segment.output_node_indices) {
                ggml_tensor* marked = ggml_graph_node(graph, cut_index);
                if (marked != nullptr && ggml_graph_cut::is_graph_cut_tensor(marked) &&
                    segment.future_cut_names.count(marked->name)) {
                    ggml_tensor* reference = ggml_graph_cut::cache_source_tensor(marked);
                    saved = cut_cache_.store_f32(marked->name, reference,
                                                  output.data(), output.size());
                    if (!saved) break;
                }
            }
            if (!saved) return fail_segment("npu output cache write");
            cut_cache_.prune(segment.future_cut_names);
            LOG_INFO("ANIMA_NPU_DIT_END block=%d elapsed_ms=%lld",
                     block_id, static_cast<long long>(ggml_time_ms() - started));
            continue;
        }
"""
exactly_once(runner, anchor, insert + anchor)
print("Patched Anima graph-cut NPU dispatch and F32 output cache injection")
