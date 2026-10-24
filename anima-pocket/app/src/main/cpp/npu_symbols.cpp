#include <jni.h>
#include <dlfcn.h>
#include <sstream>
#include <string>

// Inspect the public symbol table without invoking any proprietary NPU API.
// This runs inside Anima's isolated :npu_probe process.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_card_anima_NpuPteTest_probeVendorSymbolsNative(JNIEnv* env, jclass) {
    std::ostringstream out;
    void* library = dlopen("libneuron_runtime.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        const char* error = dlerror();
        out << "vendorRuntimeLoaded=false\n";
        out << "dlerror=" << (error ? error : "unknown") << "\n";
        out << "RESULT=VENDOR_RUNTIME_UNAVAILABLE\n";
        return env->NewStringUTF(out.str().c_str());
    }
    out << "vendorRuntimeLoaded=true\n";
    const char* required[] = {
        "Neuron_getVersion",
        "NeuronModel_create",
        "NeuronModel_addOperand",
        "NeuronModel_setOperandValue",
        "NeuronModel_addOperation",
        "NeuronModel_identifyInputsAndOutputs",
        "NeuronModel_finish",
        "NeuronModel_free",
        "NeuronModel_getExtensionOperandType",
        "NeuronModel_getExtensionOperationType",
        "NeuronCompilation_createWithOptions",
        "NeuronCompilation_setPreference",
        "NeuronCompilation_setPriority",
        "NeuronCompilation_finish",
        "NeuronCompilation_free",
        "NeuronExecution_create",
        "NeuronExecution_setInput",
        "NeuronExecution_setOutput",
        "NeuronExecution_compute",
        "NeuronExecution_free"
    };
    int present = 0;
    int missing = 0;
    for (const char* symbol : required) {
        void* found = dlsym(library, symbol);
        if (found) {
            ++present;
        } else {
            ++missing;
            out << "missingSymbol=" << symbol << "\n";
        }
    }
    out << "neuronSymbolsPresent=" << present
        << "; missing=" << missing << "\n";
    out << (missing == 0
        ? "RESULT=VENDOR_ABI_CANDIDATE_ONLY_NO_INFERENCE\n"
        : "RESULT=VENDOR_ABI_INCOMPATIBLE_WITH_EXACT_EXECUTORCH_SHIM\n");
    out << "No vendor symbols were called and no model was executed.\n";
    dlclose(library);
    return env->NewStringUTF(out.str().c_str());
}
