#include <jni.h>
#include <android/NeuralNetworks.h>
#include <algorithm>
#include <chrono>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <iomanip>
#include <memory>
#include <sstream>
#include <string>
#include <vector>
#include "ggml.h"

// NNAPI was deprecated in Android 15. This deliberately uses its strict
// createForDevices API, rather than allowing an unreported CPU fallback.
namespace {
template<class T,void(*F)(T*)>
using Scope=std::unique_ptr<T,decltype(F)>;
using Clock=std::chrono::steady_clock;
double ms(Clock::time_point t){
    return std::chrono::duration<double,std::milli>(Clock::now()-t).count();
}
std::string error(const char* phase,int code){
    return std::string(phase)+": NNAPI error "+std::to_string(code)+"\n";
}
std::string typeName(int32_t type){
    switch(type){
        case ANEURALNETWORKS_DEVICE_ACCELERATOR:return "ACCELERATOR";
        case ANEURALNETWORKS_DEVICE_GPU:return "GPU";
        case ANEURALNETWORKS_DEVICE_CPU:return "CPU";
        case ANEURALNETWORKS_DEVICE_OTHER:return "OTHER";
        default:return "UNKNOWN";
    }
}
bool isNpuCandidate(const std::string& name,int32_t type){
    if(type==ANEURALNETWORKS_DEVICE_GPU||type==ANEURALNETWORKS_DEVICE_CPU)return false;
    if(type==ANEURALNETWORKS_DEVICE_ACCELERATOR)return true;
    std::string lower=name;
    std::transform(lower.begin(),lower.end(),lower.begin(),[](unsigned char c){return (char)std::tolower(c);});
    return lower.find("neuron")!=std::string::npos ||
           lower.find("mdla")!=std::string::npos ||
           lower.find("npu")!=std::string::npos ||
           lower.find("apu")!=std::string::npos;
}
std::string bench(const ANeuralNetworksDevice* device,const std::string& name){
    constexpr uint32_t B=64,K=1024,M=1024;
    std::ostringstream report;
    report<<"candidate="<<name<<" operation=FP16_FULLY_CONNECTED B="<<B<<" K="<<K<<" M="<<M<<"\n";
    // Model contains a transformer-shaped learned projection. Values are synthetic.
    std::vector<float> wf((size_t)M*K),xf((size_t)B*K);
    for(size_t i=0;i<wf.size();i++)wf[i]=0.012f*std::sin(float(i%251)*0.071f);
    for(size_t i=0;i<xf.size();i++)xf[i]=0.053f*std::cos(float(i%313)*0.037f);
    std::vector<ggml_fp16_t> w(wf.size()),x(xf.size()),bias(M),out((size_t)B*M);
    ggml_fp32_to_fp16_row(wf.data(),w.data(),(int64_t)wf.size());
    ggml_fp32_to_fp16_row(xf.data(),x.data(),(int64_t)xf.size());
    std::fill(bias.begin(),bias.end(),ggml_fp32_to_fp16(0.0f));
    ANeuralNetworksModel* rawModel=nullptr;
    int e=ANeuralNetworksModel_create(&rawModel);
    if(e!=ANEURALNETWORKS_NO_ERROR)return error("model create",e);
    Scope<ANeuralNetworksModel,ANeuralNetworksModel_free> model(rawModel,ANeuralNetworksModel_free);
    uint32_t dimsIn[2]={B,K},dimsWeight[2]={M,K},dimsBias[1]={M},dimsOut[2]={B,M};
    const ANeuralNetworksOperandType operands[5]={
        {ANEURALNETWORKS_TENSOR_FLOAT16,2,dimsIn,0,0},
        {ANEURALNETWORKS_TENSOR_FLOAT16,2,dimsWeight,0,0},
        {ANEURALNETWORKS_TENSOR_FLOAT16,1,dimsBias,0,0},
        {ANEURALNETWORKS_INT32,0,nullptr,0,0},
        {ANEURALNETWORKS_TENSOR_FLOAT16,2,dimsOut,0,0}
    };
    for(const auto& operand:operands){
        e=ANeuralNetworksModel_addOperand(model.get(),&operand);
        if(e!=ANEURALNETWORKS_NO_ERROR)return error("add operand",e);
    }
    const int32_t activation=ANEURALNETWORKS_FUSED_NONE;
    e=ANeuralNetworksModel_setOperandValue(model.get(),1,w.data(),w.size()*sizeof(w[0]));
    if(e!=ANEURALNETWORKS_NO_ERROR)return error("set weights",e);
    e=ANeuralNetworksModel_setOperandValue(model.get(),2,bias.data(),bias.size()*sizeof(bias[0]));
    if(e!=ANEURALNETWORKS_NO_ERROR)return error("set bias",e);
    e=ANeuralNetworksModel_setOperandValue(model.get(),3,&activation,sizeof(activation));
    if(e!=ANEURALNETWORKS_NO_ERROR)return error("set activation",e);
    const uint32_t ins[4]={0,1,2,3},outs[1]={4},modelInputs[1]={0},modelOutputs[1]={4};
    e=ANeuralNetworksModel_addOperation(model.get(),ANEURALNETWORKS_FULLY_CONNECTED,4,ins,1,outs);
    if(e!=ANEURALNETWORKS_NO_ERROR)return error("add fully connected",e);
    e=ANeuralNetworksModel_identifyInputsAndOutputs(model.get(),1,modelInputs,1,modelOutputs);
    if(e!=ANEURALNETWORKS_NO_ERROR)return error("identify IO",e);
    e=ANeuralNetworksModel_finish(model.get());
    if(e!=ANEURALNETWORKS_NO_ERROR)return error("finish model",e);
    const ANeuralNetworksDevice* allowed[1]={device};
    bool supported=false;
    e=ANeuralNetworksModel_getSupportedOperationsForDevices(model.get(),allowed,1,&supported);
    if(e!=ANEURALNETWORKS_NO_ERROR)return error("query supported operations",e);
    report<<"hardwareSupportsFp16Dense="<<(supported?"true":"false")<<"\n";
    if(!supported)return report.str()+"RESULT=UNSUPPORTED_ON_HARDWARE\n";
    ANeuralNetworksCompilation* rawCompilation=nullptr;
    auto begin=Clock::now();
    e=ANeuralNetworksCompilation_createForDevices(model.get(),allowed,1,&rawCompilation);
    if(e!=ANEURALNETWORKS_NO_ERROR)return report.str()+error("hardware-only compilation create",e);
    Scope<ANeuralNetworksCompilation,ANeuralNetworksCompilation_free> compiled(rawCompilation,ANeuralNetworksCompilation_free);
    e=ANeuralNetworksCompilation_setPreference(compiled.get(),ANEURALNETWORKS_PREFER_FAST_SINGLE_ANSWER);
    if(e!=ANEURALNETWORKS_NO_ERROR)return report.str()+error("set preference",e);
    e=ANeuralNetworksCompilation_finish(compiled.get());
    report<<"compilationMs="<<ms(begin)<<"\n";
    if(e!=ANEURALNETWORKS_NO_ERROR)return report.str()+error("hardware compilation finish",e);
    double times[4]={};
    for(int i=0;i<4;i++){
        ANeuralNetworksExecution* rawExecution=nullptr;
        e=ANeuralNetworksExecution_create(compiled.get(),&rawExecution);
        if(e!=ANEURALNETWORKS_NO_ERROR)return report.str()+error("execution create",e);
        Scope<ANeuralNetworksExecution,ANeuralNetworksExecution_free> execution(rawExecution,ANeuralNetworksExecution_free);
        e=ANeuralNetworksExecution_setInput(execution.get(),0,nullptr,x.data(),x.size()*sizeof(x[0]));
        if(e!=ANEURALNETWORKS_NO_ERROR)return report.str()+error("set input",e);
        e=ANeuralNetworksExecution_setOutput(execution.get(),0,nullptr,out.data(),out.size()*sizeof(out[0]));
        if(e!=ANEURALNETWORKS_NO_ERROR)return report.str()+error("set output",e);
        auto started=Clock::now();
        e=ANeuralNetworksExecution_compute(execution.get());
        times[i]=ms(started);
        if(e!=ANEURALNETWORKS_NO_ERROR)return report.str()+error("hardware execute",e);
    }
    double sumSq=0,refSq=0,maxError=0;
    int nonfinite=0;
    for(size_t j=0;j<32;j++){
        size_t b=(j*17)%B,m=(j*103)%M;
        float expected=0;
        for(size_t k=0;k<K;k++)
            expected+=ggml_fp16_to_fp32(x[b*K+k])*ggml_fp16_to_fp32(w[m*K+k]);
        float actual=ggml_fp16_to_fp32(out[b*M+m]);
        if(!std::isfinite(actual)){nonfinite++;continue;}
        double delta=(double)actual-expected;
        sumSq+=delta*delta;refSq+=(double)expected*expected;
        maxError=std::max(maxError,std::abs(delta));
    }
    double nrmse=std::sqrt(sumSq/std::max(refSq,1e-12));
    report<<std::fixed<<std::setprecision(3)
          <<"warmupMs="<<times[0]<<" steadyMeanMs="<<(times[1]+times[2]+times[3])/3.0
          <<" normalizedRMSE="<<nrmse<<" maxAbsError="<<maxError
          <<" nonfinite="<<nonfinite<<"\n";
    report<<(nonfinite>0||nrmse>0.15
             ?"RESULT=HARDWARE_EXECUTED_BUT_INCORRECT\n"
             :"RESULT=STRICT_HARDWARE_MATMUL_SUCCESS\n");
    report<<"CPU fallback was disabled using createForDevices and an accelerator-only device list.\n";
    return report.str();
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_card_anima_MainActivity_probeNpuNative(JNIEnv* env,jobject){
    std::ostringstream out;
    out<<"Anima Pocket v0.5.0 NNAPI NPU feasibility (no model files)\n";
    uint32_t count=0;
    int e=ANeuralNetworks_getDeviceCount(&count);
    if(e!=ANEURALNETWORKS_NO_ERROR){
        out<<error("get device count",e);
        return env->NewStringUTF(out.str().c_str());
    }
    out<<"NNAPI devices="<<count<<"\n";
    std::vector<std::pair<const ANeuralNetworksDevice*,std::string>> candidates;
    for(uint32_t i=0;i<count;i++){
        ANeuralNetworksDevice* device=nullptr;
        e=ANeuralNetworks_getDevice(i,&device);
        if(e!=ANEURALNETWORKS_NO_ERROR||!device){
            out<<"device["<<i<<"] queryError="<<e<<"\n";continue;
        }
        const char *name=nullptr,*version=nullptr;
        int32_t type=ANEURALNETWORKS_DEVICE_UNKNOWN;
        int64_t feature=0;
        ANeuralNetworksDevice_getName(device,&name);
        ANeuralNetworksDevice_getVersion(device,&version);
        ANeuralNetworksDevice_getType(device,&type);
        ANeuralNetworksDevice_getFeatureLevel(device,&feature);
        std::string label=name?name:"unknown";
        out<<"device["<<i<<"]="<<label<<" type="<<typeName(type)
           <<" featureLevel="<<feature<<" version="<<(version?version:"unknown")<<"\n";
        if(isNpuCandidate(label,type))candidates.emplace_back(device,label);
    }
    if(candidates.empty()){
        out<<"RESULT=NO_NPU_VISIBLE_THROUGH_NNAPI\n";
        out<<"LiteRT/MediaTek NeuroPilot might still work through a vendor SDK, which is separate from NNAPI.\n";
    }else{
        bool success=false;
        for(const auto& d:candidates){
            const std::string result=bench(d.first,d.second);
            out<<result;
            if(result.find("RESULT=STRICT_HARDWARE_MATMUL_SUCCESS")!=std::string::npos){
                success=true;break;
            }
        }
        if(!success)out<<"RESULT=NO_SUCCESSFUL_STRICT_ACCELERATOR_RUN\n";
    }
    out<<"This does not yet execute Anima transformer blocks on NPU.\n";
    return env->NewStringUTF(out.str().c_str());
}
