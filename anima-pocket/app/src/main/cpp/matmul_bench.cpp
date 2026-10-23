#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>
#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include "stable-diffusion.h"

namespace {
struct Measurement {
    std::vector<float> output;
    std::vector<double> times;
    std::string error;
};

Measurement execute(ggml_backend_dev_t device,ggml_type type,
                    const std::vector<uint8_t>& weights,const std::vector<float>& input,
                    int k,int m,int n,int trials){
    Measurement result;
    if(!device){result.error="Backend device unavailable";return result;}
    ggml_backend_t backend=ggml_backend_dev_init(device,nullptr);
    if(!backend){result.error="Backend initialization failed";return result;}
    ggml_init_params p={8*1024*1024,nullptr,true};
    ggml_context* ctx=ggml_init(p);
    if(!ctx){ggml_backend_free(backend);result.error="GGML context unavailable";return result;}
    ggml_tensor* w=ggml_new_tensor_2d(ctx,type,k,m);
    ggml_tensor* x=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,k,n);
    ggml_tensor* y=ggml_mul_mat(ctx,w,x);
    ggml_set_name(w,"bench.weights");
    ggml_set_name(x,"bench.input");
    ggml_set_name(y,"bench.output");
    ggml_cgraph* graph=ggml_new_graph(ctx);
    ggml_build_forward_expand(graph,y);
    ggml_gallocr_t allocator=ggml_gallocr_new(ggml_backend_get_default_buffer_type(backend));
    if(!allocator){
        result.error="GGML graph allocator failed";
    }else if(!ggml_gallocr_alloc_graph(allocator,graph)){
        result.error="Backend buffer allocation failed";
    }else if(ggml_nbytes(w)!=weights.size() || ggml_nbytes(x)!=input.size()*sizeof(float)){
        result.error="Input tensor dimensions mismatch";
    }else{
        ggml_backend_tensor_set(w,weights.data(),0,weights.size());
        ggml_backend_tensor_set(x,input.data(),0,input.size()*sizeof(float));
        for(int i=0;i<trials;i++){
            auto begin=std::chrono::steady_clock::now();
            ggml_status status=ggml_backend_graph_compute(backend,graph);
            ggml_backend_synchronize(backend);
            auto end=std::chrono::steady_clock::now();
            if(status!=GGML_STATUS_SUCCESS){
                result.error="Graph failed with status "+std::to_string((int)status);
                break;
            }
            result.times.push_back(
                std::chrono::duration<double,std::milli>(end-begin).count());
        }
        if(result.error.empty()){
            result.output.resize((size_t)m*n);
            ggml_backend_tensor_get(y,result.output.data(),0,result.output.size()*sizeof(float));
        }
    }
    if(allocator)ggml_gallocr_free(allocator);
    ggml_free(ctx);
    ggml_backend_free(backend);
    return result;
}

std::string one(ggml_backend_dev_t gpu,ggml_backend_dev_t cpu,ggml_type type,
                const std::vector<float>& raw,const std::vector<float>& input,
                int k,int m,int n,const std::string& name){
    std::vector<uint8_t> weights(ggml_row_size(type,k)*(size_t)m);
    if(type==GGML_TYPE_Q4_0){
        size_t written=ggml_quantize_chunk(type,raw.data(),weights.data(),0,m,k,nullptr);
        if(written!=weights.size())return name+": quantization produced unexpected byte count";
    }else{
        std::vector<ggml_fp16_t> halves(raw.size());
        ggml_fp32_to_fp16_row(raw.data(),halves.data(),(int64_t)raw.size());
        std::memcpy(weights.data(),halves.data(),weights.size());
    }
    Measurement g=execute(gpu,type,weights,input,k,m,n,4);
    if(!g.error.empty())return name+": GPU error: "+g.error;
    Measurement c=execute(cpu,type,weights,input,k,m,n,1);
    if(!c.error.empty())return name+": CPU reference error: "+c.error;
    if(g.output.size()!=c.output.size())return name+": output size mismatch";
    double maxError=0.0,squared=0.0,refSquared=0.0;
    size_t nonfinite=0;
    for(size_t i=0;i<g.output.size();i++){
        if(!std::isfinite(g.output[i])||!std::isfinite(c.output[i])){nonfinite++;continue;}
        double diff=double(g.output[i])-double(c.output[i]);
        maxError=std::max(maxError,std::abs(diff));
        squared+=diff*diff;
        refSquared+=double(c.output[i])*double(c.output[i]);
    }
    std::ostringstream report;
    report<<std::fixed<<std::setprecision(3)<<name<<" K="<<k<<" M="<<m<<" N="<<n;
    report<<" firstMs="<<g.times[0];
    report<<" steadyMeanMs="<<((g.times[1]+g.times[2]+g.times[3])/3.0);
    report<<" cpuReferenceMs="<<c.times[0];
    report<<" rmse="<<std::sqrt(squared/std::max<size_t>(1,g.output.size()-nonfinite));
    report<<" normalizedRMSE="<<std::sqrt(squared/std::max(refSquared,1e-12));
    report<<" maxAbsError="<<maxError<<" nonfinite="<<nonfinite;
    return report.str();
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_card_anima_MainActivity_benchmarkMatmulNative(JNIEnv* env,jobject){
    // Initialize GGML's Vulkan plugin using the already-linked sd runtime.
    size_t count=sd_list_devices(nullptr,0);
    std::vector<char> probe(count+1,0);
    if(count)sd_list_devices(probe.data(),probe.size());
    ggml_backend_dev_t gpu=ggml_backend_dev_by_name("Vulkan0");
    ggml_backend_dev_t cpu=ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if(!gpu||!cpu){
        std::string failed=std::string("GPU/CPU backend missing. Devices: ")+probe.data();
        return env->NewStringUTF(failed.c_str());
    }
    constexpr int k=2048,m=2048,n=128;
    std::vector<float> raw((size_t)k*m);
    std::vector<float> input((size_t)k*n);
    for(size_t i=0;i<raw.size();i++)raw[i]=std::sin(float(i%791)*0.031f)*0.09f;
    for(size_t i=0;i<input.size();i++)input[i]=std::cos(float(i%521)*0.037f)*0.13f;
    std::string q4=one(gpu,cpu,GGML_TYPE_Q4_0,raw,input,k,m,n,"Q4_0");
    std::string fp16=one(gpu,cpu,GGML_TYPE_F16,raw,input,k,m,n,"FP16");
    std::string report="Isolated GGML Vulkan matmul (3 warmed GPU iterations, CPU reference):\n"+
        q4+"\n"+fp16+
        "\nSame dimensions and data; reports both speed and numerical error. Small synthetic test, not full Anima throughput.";
    return env->NewStringUTF(report.c_str());
}
