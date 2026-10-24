#include <jni.h>
#include <dlfcn.h>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>

// Based on the public Neuron Adapter API used by KataDroid (MT6989/Android 16)
// and alpharomercoma/poco-phone-ai-training (MIT). No SDK library is bundled.
// All calls run in Anima's disposable :npu_probe process.
namespace {
struct NeuronRuntimeVersion { uint8_t major, minor, patch; };
struct NeuronOperandType {
    int32_t type;
    uint32_t dimensionCount;
    const uint32_t* dimensions;
    float scale;
    int32_t zeroPoint;
};

constexpr const char* kAdapters[] = {
    "libneuronusdk_adapter.mtk.so",  // Phone's public uSDK (prefer to older MGVI).
    "libneuron_adapter_mgvi.so",
    "libneuron_adapter.so"
};

constexpr const char* kCoreSymbols[] = {
    "Neuron_getDeviceCount", "Neuron_getDevice", "NeuronDevice_getName",
    "NeuronModel_create", "NeuronModel_addOperand", "NeuronModel_setOperandValue",
    "NeuronModel_addOperation", "NeuronModel_identifyInputsAndOutputs",
    "NeuronModel_finish", "NeuronCompilation_createForDevices",
    "NeuronCompilation_finish", "NeuronExecution_create",
    "NeuronExecution_setInput", "NeuronExecution_setOutput",
    "NeuronExecution_compute"
};

jstring finish(JNIEnv* env, const std::ostringstream& stream) {
    return env->NewStringUTF(stream.str().c_str());
}

template <typename T> bool resolve(void* library, const char* name, T& output) {
    output = reinterpret_cast<T>(dlsym(library, name));
    return output != nullptr;
}

struct Api {
    int (*getVersion)(NeuronRuntimeVersion*) = nullptr;
    int (*getDeviceCount)(uint32_t*) = nullptr;
    int (*getDevice)(uint32_t, void**) = nullptr;
    int (*getDeviceName)(const void*, const char**) = nullptr;
    int (*createModel)(void**) = nullptr;
    int (*addOperand)(void*, const NeuronOperandType*) = nullptr;
    int (*setOperandValue)(void*, int32_t, const void*, size_t) = nullptr;
    int (*addOperation)(void*, int32_t, uint32_t, const uint32_t*, uint32_t, const uint32_t*) = nullptr;
    int (*identifyInputsOutputs)(void*, uint32_t, const uint32_t*, uint32_t, const uint32_t*) = nullptr;
    int (*finishModel)(void*) = nullptr;
    int (*createCompilationForDevices)(void*, const void* const*, uint32_t, void**) = nullptr;
    int (*finishCompilation)(void*) = nullptr;
    int (*createExecution)(void*, void**) = nullptr;
    int (*setInput)(void*, int32_t, const void*, const void*, size_t) = nullptr;
    int (*setOutput)(void*, int32_t, const void*, void*, size_t) = nullptr;
    int (*compute)(void*) = nullptr;
    void (*freeModel)(void*) = nullptr;
    void (*freeCompilation)(void*) = nullptr;
    void (*freeExecution)(void*) = nullptr;

    bool bind(void* library, std::ostringstream& report) {
        bool ok = true;
#define REQUIRED(member, symbol) \
        if (!resolve(library, #symbol, member)) { report << "missing=" #symbol "\n"; ok = false; }
        REQUIRED(getDeviceCount, Neuron_getDeviceCount)
        REQUIRED(getDevice, Neuron_getDevice)
        REQUIRED(getDeviceName, NeuronDevice_getName)
        REQUIRED(createModel, NeuronModel_create)
        REQUIRED(addOperand, NeuronModel_addOperand)
        REQUIRED(setOperandValue, NeuronModel_setOperandValue)
        REQUIRED(addOperation, NeuronModel_addOperation)
        REQUIRED(identifyInputsOutputs, NeuronModel_identifyInputsAndOutputs)
        REQUIRED(finishModel, NeuronModel_finish)
        REQUIRED(createCompilationForDevices, NeuronCompilation_createForDevices)
        REQUIRED(finishCompilation, NeuronCompilation_finish)
        REQUIRED(createExecution, NeuronExecution_create)
        REQUIRED(setInput, NeuronExecution_setInput)
        REQUIRED(setOutput, NeuronExecution_setOutput)
        REQUIRED(compute, NeuronExecution_compute)
#undef REQUIRED
        resolve(library, "Neuron_getVersion", getVersion);
        resolve(library, "NeuronModel_free", freeModel);
        resolve(library, "NeuronCompilation_free", freeCompilation);
        resolve(library, "NeuronExecution_free", freeExecution);
        return ok;
    }
};

struct Session {
    Api api;
    void* library = nullptr;
    void* model = nullptr;
    void* compilation = nullptr;
    void* execution = nullptr;
    ~Session() {
        if (execution && api.freeExecution) api.freeExecution(execution);
        if (compilation && api.freeCompilation) api.freeCompilation(compilation);
        if (model && api.freeModel) api.freeModel(model);
        if (library) dlclose(library);
    }
};
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_card_anima_NpuPteTest_probeVendorSymbolsNative(JNIEnv* env, jclass) {
    std::ostringstream report;
    report << "stage=phone_public_neuron_adapter_abi_only\n"
           << "bundledAdapter=false\n";
    bool complete = false;
    for (const char* name : kAdapters) {
        void* h = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        if (!h) {
            const char* error = dlerror();
            report << "adapter=" << name << "; load=false; reason="
                   << (error ? error : "unknown") << "\n";
            continue;
        }
        int present = 0;
        for (const char* symbol : kCoreSymbols) {
            if (dlsym(h, symbol)) ++present;
            else report << "missing[" << name << "]=" << symbol << "\n";
        }
        report << "adapter=" << name << "; load=true; requiredPresent="
               << present << "/" << (sizeof(kCoreSymbols) / sizeof(kCoreSymbols[0])) << "\n";
        if (present == static_cast<int>(sizeof(kCoreSymbols) / sizeof(kCoreSymbols[0]))) {
            complete = true;
            report << "selectedCandidate=" << name << "\n";
            dlclose(h);
            break; // KataDroid's fix: never overwrite the usable first adapter.
        }
        dlclose(h);
    }
    report << "RESULT=" << (complete ? "PUBLIC_NEURON_ADAPTER_API_FOUND" :
                            "NO_COMPATIBLE_PUBLIC_NEURON_ADAPTER") << "\n"
           << "No vendor symbols called; no model executed.\n";
    return finish(env, report);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_card_anima_NpuPteTest_runVendorFp16Native(JNIEnv* env, jclass) {
    std::ostringstream report;
    report << "ANIMA_NPU_DIRECT_NEURON_FP16\n"
           << "basis=KataDroid public adapter selection + POCO FP16 hardware test\n"
           << "bundledAdapter=false; cpuFallback=false\n";
    Session s;
    const char* selected = nullptr;
    for (const char* name : kAdapters) {
        void* h = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        if (!h) {
            const char* error = dlerror();
            report << "adapterLoad[" << name << "]="
                   << (error ? error : "failed") << "\n";
            continue;
        }
        Api candidate;
        std::ostringstream missing;
        if (candidate.bind(h, missing)) {
            s.library = h;
            s.api = candidate;
            selected = name;
            break; // Do not replace a working public uSDK with older MGVI.
        }
        report << "adapterIncomplete[" << name << "]\n" << missing.str();
        dlclose(h);
    }
    if (!s.library) {
        report << "RESULT=NO_COMPATIBLE_PUBLIC_ADAPTER; no inference\n";
        return finish(env, report);
    }
    report << "selectedAdapter=" << selected << "\n";
    // Avoid calling vendor-version APIs with SDK-specific struct layouts.
    // Probe only the stable public Neuron execution ABI.
    uint32_t count = 0;
    int rc = s.api.getDeviceCount(&count);
    if (rc != 0 || count == 0 || count > 32) {
        report << "RESULT=DEVICE_ENUMERATION_FAILED; rc=" << rc << "; count=" << count << "\n";
        return finish(env, report);
    }
    void* mdla = nullptr;
    const char* deviceName = nullptr;
    for (uint32_t i = 0; i < count; ++i) {
        void* device = nullptr;
        const char* name = nullptr;
        if (s.api.getDevice(i, &device) == 0 && device &&
            s.api.getDeviceName(device, &name) == 0 && name) {
            report << "device[" << i << "]=" << name << "\n";
            if (!mdla && std::strstr(name, "mtk-mdla")) {
                mdla = device;
                deviceName = name;
            }
        }
    }
    if (!mdla) {
        report << "RESULT=NO_MDLA_DEVICE; will not use CPU/GPU fallback\n";
        return finish(env, report);
    }
    report << "selectedHardware=" << deviceName << "\n";

    constexpr int M = 16, K = 512, N = 512;
    static __fp16 weights[N * K], biases[N], inputs[M * K], outputs[M * N];
    for (int i = 0; i < N * K; ++i)
        weights[i] = static_cast<__fp16>((i % 7 - 3) * 0.1f);
    for (int i = 0; i < N; ++i) biases[i] = static_cast<__fp16>(0.01f);
    for (int i = 0; i < M * K; ++i)
        inputs[i] = static_cast<__fp16>((i % 5 - 2) * 0.2f);
    std::memset(outputs, 0, sizeof(outputs));

    auto failure = [&](const char* stage, int code) -> jstring {
        report << "RESULT=NEURON_OPERATION_FAILED; stage=" << stage
               << "; rc=" << code << "\n";
        return finish(env, report);
    };
    rc = s.api.createModel(&s.model);
    if (rc != 0 || !s.model) return failure("NeuronModel_create", rc);

    uint32_t inDims[2] = {M, K}, weightDims[2] = {N, K},
             biasDims[1] = {N}, outDims[2] = {M, N};
    // NeuronAdapter constants match NNAPI: TENSOR_FLOAT16=8, INT32=1, FC=9.
    NeuronOperandType inType{8, 2, inDims, 0, 0};
    NeuronOperandType weightType{8, 2, weightDims, 0, 0};
    NeuronOperandType biasType{8, 1, biasDims, 0, 0};
    NeuronOperandType activationType{1, 0, nullptr, 0, 0};
    NeuronOperandType outType{8, 2, outDims, 0, 0};
    const NeuronOperandType types[] = {inType, weightType, biasType, activationType, outType};
    for (int i = 0; i < 5; ++i) {
        rc = s.api.addOperand(s.model, &types[i]);
        if (rc) return failure("NeuronModel_addOperand", rc);
    }
    rc = s.api.setOperandValue(s.model, 1, weights, sizeof(weights));
    if (rc) return failure("NeuronModel_setOperandValue_weights", rc);
    rc = s.api.setOperandValue(s.model, 2, biases, sizeof(biases));
    if (rc) return failure("NeuronModel_setOperandValue_bias", rc);
    int32_t none = 0;
    rc = s.api.setOperandValue(s.model, 3, &none, sizeof(none));
    if (rc) return failure("NeuronModel_setOperandValue_activation", rc);
    uint32_t opInputs[4] = {0, 1, 2, 3}, opOutputs[1] = {4};
    rc = s.api.addOperation(s.model, 9, 4, opInputs, 1, opOutputs);
    if (rc) return failure("NeuronModel_addOperation_FC", rc);
    uint32_t modelInputs[1] = {0}, modelOutputs[1] = {4};
    rc = s.api.identifyInputsOutputs(s.model, 1, modelInputs, 1, modelOutputs);
    if (rc) return failure("NeuronModel_identifyInputsAndOutputs", rc);
    rc = s.api.finishModel(s.model);
    if (rc) return failure("NeuronModel_finish", rc);

    const void* devices[1] = {mdla};
    auto tick = std::chrono::steady_clock::now();
    rc = s.api.createCompilationForDevices(s.model, devices, 1, &s.compilation);
    if (rc || !s.compilation) return failure("NeuronCompilation_createForDevices_MDLA", rc);
    rc = s.api.finishCompilation(s.compilation);
    if (rc) return failure("NeuronCompilation_finish", rc);
    auto compileEnd = std::chrono::steady_clock::now();
    rc = s.api.createExecution(s.compilation, &s.execution);
    if (rc || !s.execution) return failure("NeuronExecution_create", rc);
    rc = s.api.setInput(s.execution, 0, nullptr, inputs, sizeof(inputs));
    if (rc) return failure("NeuronExecution_setInput", rc);
    rc = s.api.setOutput(s.execution, 0, nullptr, outputs, sizeof(outputs));
    if (rc) return failure("NeuronExecution_setOutput", rc);
    rc = s.api.compute(s.execution);
    if (rc) return failure("NeuronExecution_compute", rc);
    auto computeEnd = std::chrono::steady_clock::now();

    int mismatches = 0;
    float maxAbsError = 0.0f;
    double checksum = 0.0;
    for (int m = 0; m < M; ++m) {
        for (int n = 0; n < N; ++n) {
            float golden = static_cast<float>(biases[n]);
            for (int k = 0; k < K; ++k)
                golden += static_cast<float>(inputs[m * K + k]) *
                          static_cast<float>(weights[n * K + k]);
            float actual = static_cast<float>(outputs[m * N + n]);
            float err = std::fabs(golden - actual);
            if (!std::isfinite(actual) || err > 0.20f + 0.075f * std::fabs(golden))
                ++mismatches;
            if (std::isfinite(err) && err > maxAbsError) maxAbsError = err;
            checksum += actual;
        }
    }
    report << "input=[16,512] FP16; weights=[512,512] FP16; output=[16,512] FP16\n";
    report << "compilationMs="
           << std::chrono::duration<double, std::milli>(compileEnd - tick).count()
           << "; inferenceMs="
           << std::chrono::duration<double, std::milli>(computeEnd - compileEnd).count() << "\n";
    report << "cpuReferenceMismatches=" << mismatches
           << "; maxAbsError=" << maxAbsError
           << "; checksum=" << checksum << "\n";
    report << "RESULT=" << (mismatches == 0 ? "MDLA_FP16_INFERENCE_VERIFIED" :
                             "MDLA_OUTPUT_MISMATCH") << "\n";
    report << "scope=REAL NPU math, synthetic weights; NOT Anima image generation\n";
    return finish(env, report);
}
