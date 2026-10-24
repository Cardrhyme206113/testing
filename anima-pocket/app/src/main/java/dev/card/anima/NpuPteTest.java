package dev.card.anima;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import org.pytorch.executorch.EValue;
import org.pytorch.executorch.ExecutorchRuntimeException;
import org.pytorch.executorch.ExecuTorchRuntime;
import org.pytorch.executorch.Module;
import org.pytorch.executorch.Tensor;

/** Real .pte delegate execution, deliberately separate from Vulkan image generation. */
public final class NpuPteTest {
    private NpuPteTest(){}

    /** Reads exported symbol names only; never calls Neuron functions or loads a model. */
    private static native String probeVendorSymbolsNative();

    /** Backend-owned counters, exported by our instrumented v1.5.1 libneuron_backend.so. */
    private static native long[] nativeNeuronTrace();

    /** Executes a real FP16 fully-connected layer on MDLA, with no CPU fallback. */
    private static native String runVendorFp16Native();

    /** Each loader check is executed in a fresh :npu_probe process. */
    public static String probeLoader(String mode) {
        long begin=System.nanoTime();
        StringBuilder report=new StringBuilder("ANIMA_NPU_LOADER_MODE=").append(mode).append("\n");
        switch(mode) {
            case "vendor_runtime":
                report.append("stage=load_system_public_neuron_runtime\n");
                System.loadLibrary("neuron_runtime");
                report.append("library=neuron_runtime; loaded=true\n");
                break;
            case "vendor_symbols":
                report.append("stage=inspect_device_neuron_api_symbols_no_execution\n");
                System.loadLibrary("npusymbols");
                report.append(probeVendorSymbolsNative());
                break;
            case "vendor_fp16":
                report.append("stage=execute_direct_phone_neuron_fp16_on_mdla\n");
                System.loadLibrary("npusymbols");
                report.append(runVendorFp16Native());
                break;
            case "allocator_direct":
                report.append("stage=load_bundled_neuron_buffer_allocator\n");
                System.loadLibrary("neuron_buffer_allocator");
                report.append("library=neuron_buffer_allocator; loaded=true\n");
                break;
            case "et_lazy":
                report.append("stage=executorch_backend_discovery_without_neuron_preload\n");
                report.append("registeredBackends=")
                    .append(Arrays.toString(ExecuTorchRuntime.getRegisteredBackends())).append("\n");
                break;
            case "backend_direct":
                report.append("stage=load_bundled_neuron_backend_without_adapter_preload\n");
                System.loadLibrary("neuron_backend");
                report.append("library=neuron_backend; loaded=true\n");
                report.append("registeredBackendsAfterLoad=")
                    .append(Arrays.toString(ExecuTorchRuntime.getRegisteredBackends())).append("\n");
                break;
            case "adapter_direct":
                report.append("stage=load_phone_public_neuron_adapter_unsafe\n");
                System.loadLibrary("neuronusdk_adapter.mtk");
                report.append("library=neuronusdk_adapter.mtk; loaded=true\n");
                break;
            default:
                throw new IllegalArgumentException("Unknown loader mode: "+mode);
        }
        if (!"vendor_fp16".equals(mode))
            report.append("status=LOADER_CHECK_SUCCEEDED_NOT_MODEL_INFERENCE\n");
        report.append("wallMs=").append(String.format(Locale.US,"%.1f",(System.nanoTime()-begin)/1e6));
        return report.toString();
    }

    /** Execute one real 1024-token A16W4 transformer block in disposable process.
     * Six inputs match compile_transformer_blocks.py exactly.
     * A successful forward is not, by itself, proof every graph op was NPU-delegated.
     */
    private static String runTransformerBlock(File modelFile, boolean backendFirst) {
        long start = System.nanoTime();
        StringBuilder report = new StringBuilder("ANIMA_REAL_DIT_BLOCK_A16W4_TEST\n");
        report.append("model_bytes=").append(modelFile.length()).append("\n");
        report.append("inputs=x[1,1024,2048],context[1,512,1024],step[1,2048],");
        report.append("temb[1,6144],cos[1024,128],sin[1024,128] FP32\n");
        report.append("load_route=").append(backendFirst ? "backend_first" : "executorch_lazy").append("\n");
        if (backendFirst) System.loadLibrary("neuron_backend");
        String[] registered = ExecuTorchRuntime.getRegisteredBackends();
        // Make the JNI trace entry point visible in either loader route.
        System.loadLibrary("neuron_backend");
        long[] traceBeforeLoad = nativeNeuronTrace();
        report.append("registered_backends=").append(Arrays.toString(registered)).append("\n");
        if (!Arrays.asList(registered).contains("NeuropilotBackend")) {
            throw new IllegalStateException("NeuropilotBackend absent from ExecuTorch runtime");
        }

        // Small deterministic amplitudes exercise non-zero residual and MLP paths.
        Random random = new Random(4026);
        float[] x = new float[1024 * 2048];
        float[] context = new float[512 * 1024];
        float[] step = new float[2048];
        float[] temb = new float[6144];
        for (int i = 0; i < x.length; i++) x[i] = (float)(random.nextGaussian() * 0.015);
        for (int i = 0; i < context.length; i++) context[i] = (float)(random.nextGaussian() * 0.015);
        for (int i = 0; i < step.length; i++) step[i] = (float)(random.nextGaussian() * 0.015);
        for (int i = 0; i < temb.length; i++) temb[i] = (float)(random.nextGaussian() * 0.015);
        float[] cos = new float[1024 * 128];
        float[] sin = new float[1024 * 128];
        for (int token = 0; token < 1024; token++) {
            for (int d = 0; d < 128; d++) {
                double angle = token * Math.pow(10000.0, -(d % 64) / 64.0) * 0.01;
                cos[token * 128 + d] = (float)Math.cos(angle);
                sin[token * 128 + d] = (float)Math.sin(angle);
            }
        }
        EValue[] input = {
            EValue.from(Tensor.fromBlob(x, new long[]{1, 1024, 2048})),
            EValue.from(Tensor.fromBlob(context, new long[]{1, 512, 1024})),
            EValue.from(Tensor.fromBlob(step, new long[]{1, 2048})),
            EValue.from(Tensor.fromBlob(temb, new long[]{1, 6144})),
            EValue.from(Tensor.fromBlob(cos, new long[]{1024, 128})),
            EValue.from(Tensor.fromBlob(sin, new long[]{1024, 128}))
        };
        Module module = Module.load(modelFile.getAbsolutePath(), Module.LOAD_MODE_MMAP, 1);
        long[] traceAfterLoad = nativeNeuronTrace();
        try {
            double[] forwardMs = new double[3];
            double firstChecksum = Double.NaN;
            for (int iteration = 0; iteration < forwardMs.length; iteration++) {
                long tick = System.nanoTime();
                EValue[] output = module.forward(input);
                forwardMs[iteration] = (System.nanoTime() - tick) / 1e6;
                if (output == null || output.length != 1 || !output[0].isTensor()) {
                    throw new IllegalStateException("Expected one output tensor, received " +
                        (output == null ? "null" : output.length));
                }
                float[] result = output[0].toTensor().getDataAsFloatArray();
                if (result.length != 1024 * 2048) {
                    throw new IllegalStateException("Wrong output length " + result.length);
                }
                double sum = 0, squared = 0, maxAbs = 0, deltaSum = 0;
                for (int i = 0; i < result.length; i++) {
                    float value = result[i];
                    if (!Float.isFinite(value)) {
                        throw new IllegalStateException("Invalid output at " + i + ": " + value);
                    }
                    sum += value;
                    squared += (double)value * value;
                    maxAbs = Math.max(maxAbs, Math.abs(value));
                    deltaSum += Math.abs((double)value - x[i]);
                }
                double checksum = sum / result.length + squared / result.length;
                if (iteration == 0) firstChecksum = checksum;
                else if (Math.abs(checksum - firstChecksum) > 1e-7) {
                    throw new IllegalStateException("Forward outputs changed across identical inputs");
                }
                if (iteration == 0) {
                    report.append("output_mean=").append(sum / result.length);
                    report.append("; output_rms=").append(Math.sqrt(squared / result.length));
                    report.append("; output_max_abs=").append(maxAbs);
                    report.append("; mean_delta_from_input=").append(deltaSum / result.length).append("\n");
                }
            }
            long[] traceAfterRuns = nativeNeuronTrace();
            if (traceAfterRuns == null || traceBeforeLoad == null ||
                traceAfterLoad == null || traceAfterRuns.length < 4 ||
                traceBeforeLoad.length < 4 || traceAfterLoad.length < 4) {
                throw new IllegalStateException("Instrumented Neuron backend returned no counters");
            }
            long directCalls = traceAfterRuns[1] - traceAfterLoad[1];
            long newDelegates = traceAfterLoad[0] - traceBeforeLoad[0];
            long errors = traceAfterRuns[2] - traceAfterLoad[2];
            long computeNs = traceAfterRuns[3] - traceAfterLoad[3];
            report.append("forward_1_ms=").append(String.format(Locale.US, "%.3f", forwardMs[0]));
            report.append("; forward_2_ms=").append(String.format(Locale.US, "%.3f", forwardMs[1]));
            report.append("; forward_3_ms=").append(String.format(Locale.US, "%.3f", forwardMs[2])).append("\n");
            report.append("compiled_delegate_initializations=").append(newDelegates);
            report.append("; vendor_neuron_compute_calls=").append(directCalls);
            report.append("; vendor_compute_errors=").append(errors);
            report.append("; vendor_compute_ms=").append(String.format(Locale.US, "%.3f", computeNs / 1e6)).append("\n");
            if (directCalls < 3 || errors != 0) {
                throw new IllegalStateException("Not all forwards reached NeuronExecution_compute: " + directCalls);
            }
            report.append("status=NEURON_EXECUTION_COMPUTE_VERIFIED\n");
            report.append("note=Native backend called vendor NeuronExecution_compute on the compiled model; ");
            report.append("the SDK may still choose internal NPU/DSP partitions.\n");
        } finally {
            module.close();
        }
        report.append("wall_ms=").append(String.format(Locale.US, "%.1f", (System.nanoTime()-start)/1e6));
        return report.toString();
    }

    /** No explicit bundled adapter preload; see whether ExecuTorch can initialize it. */
    public static String run(File modelFile, boolean backendFirst) {
        if (modelFile.length() > 5L * 1024L * 1024L) {
            return runTransformerBlock(modelFile, backendFirst);
        }
        long start=System.nanoTime();
        StringBuilder report=new StringBuilder("ANIMA_REAL_WEIGHT_A16W4_NPU\n");
        report.append("loadRoute=").append(backendFirst?"backend_first":"executorch_lazy").append("\n");
        // Old build explicitly loaded the adapter here; its ELF constructor
        // jumped to NULL on Xiaomi Android 16. Skip that direct preload.
        if(backendFirst)System.loadLibrary("neuron_backend");
        String[] registered=ExecuTorchRuntime.getRegisteredBackends();
        report.append("registeredBackends=").append(Arrays.toString(registered)).append("\n");
        if(!Arrays.asList(registered).contains("NeuropilotBackend")){
            throw new IllegalStateException("NeuropilotBackend absent in ExecuTorch runtime: "+Arrays.toString(registered));
        }
        // Exporter uses B=16, K=1024, M=2048 for the real Anima first-block attention projection.
        float[] input=new float[16*1024];
        Random random=new Random(42);
        for(int i=0;i<input.length;i++)input[i]=(float)(random.nextGaussian()*0.3);
        float[] result;
        long firstNs, secondNs;
        Module module=Module.load(modelFile.getAbsolutePath(),Module.LOAD_MODE_MMAP,1);
        try {
            EValue tensor=EValue.from(Tensor.fromBlob(input,new long[]{16,1024}));
            long tick=System.nanoTime();
            EValue[] values=module.forward(tensor);
            firstNs=System.nanoTime()-tick;
            if(values==null || values.length!=1 || !values[0].isTensor())throw new IllegalStateException("Unexpected projection output signature");
            result=values[0].toTensor().getDataAsFloatArray();
            tick=System.nanoTime();
            EValue[] again=module.forward(tensor);
            secondNs=System.nanoTime()-tick;
            if(again.length!=1 || !again[0].isTensor())throw new IllegalStateException("Invalid warmup output");
        }finally{module.close();}
        if(result.length!=16*2048)throw new IllegalStateException("Unexpected output count: "+result.length+" expected 32768");
        double sum=0,squared=0;
        for(float x:result){
            if(!Float.isFinite(x))throw new IllegalStateException("NPU returned NaN/Infinity");
            sum+=x;squared+=((double)x)*x;
        }
        report.append("input=[16,1024] FP32; output=[16,2048] FP32\n");
        report.append("npu_firstMs=").append(String.format(Locale.US,"%.3f",firstNs/1e6));
        report.append("; npu_warmMs=").append(String.format(Locale.US,"%.3f",secondNs/1e6)).append("\n");
        report.append("output_mean=").append(sum/result.length);
        report.append("; output_rms=").append(Math.sqrt(squared/result.length)).append("\n");
        report.append("status=REAL_PTE_EXECUTED_ON_REGISTERED_NEUROPILOT_BACKEND\n");
        report.append("wallMs=").append(String.format(Locale.US,"%.1f",(System.nanoTime()-start)/1e6)).append("\n");
        report.append("scope=ONE Anima attention projection; NOT image generation\n");
        return report.toString();
    }
}
