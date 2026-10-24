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
                report.append("stage=load_bundled_neuronusdk_adapter_known_crash\n");
                System.loadLibrary("neuronusdk_adapter.mtk");
                report.append("library=neuronusdk_adapter.mtk; loaded=true\n");
                break;
            default:
                throw new IllegalArgumentException("Unknown loader mode: "+mode);
        }
        report.append("status=LOADER_CHECK_SUCCEEDED_NOT_MODEL_INFERENCE\n");
        report.append("wallMs=").append(String.format(Locale.US,"%.1f",(System.nanoTime()-begin)/1e6));
        return report.toString();
    }

    /** No explicit bundled adapter preload; see whether ExecuTorch can initialize it. */
    public static String run(File modelFile, boolean backendFirst) {
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
