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
std::string bench(const ANeuralNetworksDevice* device,const std::string& name,
                  uint32_t B,uint32_t K,uint32_t M){
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

// Actual operations used in Anima's 28 transformer blocks. Each model
// contains just one operation, so getSupportedOperationsForDevices cannot
// hide a partially supported multi-op graph. Compilations are device-only.
std::string probeOperator(const ANeuralNetworksDevice* dev,int kind){
    static const char* names[]={"BATCH_MATMUL","SOFTMAX","ADD","MUL",
                                "MEAN","RSQRT","TRANSPOSE","LOGISTIC",
                                "TANH","RESHAPE"};
    const char* label=names[kind];
    int32_t code=ANEURALNETWORKS_ADD;
    switch(kind){
        case 0:code=ANEURALNETWORKS_BATCH_MATMUL;break;
        case 1:code=ANEURALNETWORKS_SOFTMAX;break;
        case 2:code=ANEURALNETWORKS_ADD;break;
        case 3:code=ANEURALNETWORKS_MUL;break;
        case 4:code=ANEURALNETWORKS_MEAN;break;
        case 5:code=ANEURALNETWORKS_RSQRT;break;
        case 6:code=ANEURALNETWORKS_TRANSPOSE;break;
        case 7:code=ANEURALNETWORKS_LOGISTIC;break;
        case 8:code=ANEURALNETWORKS_TANH;break;
        case 9:code=ANEURALNETWORKS_RESHAPE;break;
    }
    ANeuralNetworksModel* raw=nullptr;
    int e=ANeuralNetworksModel_create(&raw);
    if(e!=ANEURALNETWORKS_NO_ERROR)return std::string(label)+" modelCreateError="+std::to_string(e)+"\n";
    Scope<ANeuralNetworksModel,ANeuralNetworksModel_free> model(raw,ANeuralNetworksModel_free);
    const uint32_t flat[2]={16,128}, left[3]={1,16,128},right[3]={1,128,16},
                   matOut[3]={1,16,16},redOut[2]={16,1},
                   permOut[3]={1,128,16},s3[1]={3},s2[1]={2},s1[1]={1};
    std::vector<ANeuralNetworksOperandType> types;
    std::vector<uint32_t> opInputs;
    std::vector<uint32_t> ioInputs;
    // Add operand descriptors in the same order as operation inputs.
    const auto tensor=[&](uint32_t rank,const uint32_t* dims){
        types.push_back({ANEURALNETWORKS_TENSOR_FLOAT16,rank,dims,0.0f,0});
    };
    const auto scalar=[&](int32_t type){
        types.push_back({type,0,nullptr,0.0f,0});
    };
    const auto intTensor=[&](uint32_t rank,const uint32_t* dims){
        types.push_back({ANEURALNETWORKS_TENSOR_INT32,rank,dims,0.0f,0});
    };
    uint16_t beta=ggml_fp32_to_fp16(1.0f);
    int32_t axis=-1,keep=1,activation=ANEURALNETWORKS_FUSED_NONE;
    uint8_t falseFlag=0;
    int32_t axes[1]={1}, permutation[3]={0,2,1}, reshape[2]={16,128};
    const auto unary=[&](){tensor(2,flat);tensor(2,flat);opInputs={0};ioInputs={0};};
    if(kind==0){
        tensor(3,left);tensor(3,right);
        scalar(ANEURALNETWORKS_BOOL);scalar(ANEURALNETWORKS_BOOL);
        tensor(3,matOut);
        opInputs={0,1,2,3};ioInputs={0,1};
    }else if(kind==1){
        tensor(2,flat);scalar(ANEURALNETWORKS_FLOAT16);
        scalar(ANEURALNETWORKS_INT32);tensor(2,flat);
        opInputs={0,1,2};ioInputs={0};
    }else if(kind==2||kind==3){
        tensor(2,flat);tensor(2,flat);scalar(ANEURALNETWORKS_INT32);
        tensor(2,flat);
        opInputs={0,1,2};ioInputs={0,1};
    }else if(kind==4){
        tensor(2,flat);intTensor(1,s1);scalar(ANEURALNETWORKS_INT32);
        tensor(2,redOut);
        opInputs={0,1,2};ioInputs={0};
    }else if(kind==6){
        tensor(3,left);intTensor(1,s3);tensor(3,permOut);
        opInputs={0,1};ioInputs={0};
    }else if(kind==9){
        tensor(3,left);intTensor(1,s2);tensor(2,flat);
        opInputs={0,1};ioInputs={0};
    }else{
        unary();
    }
    for(const auto& type:types){
        e=ANeuralNetworksModel_addOperand(model.get(),&type);
        if(e!=ANEURALNETWORKS_NO_ERROR)return std::string(label)+" addOperandError="+std::to_string(e)+"\n";
    }
    if(kind==0){
        if((e=ANeuralNetworksModel_setOperandValue(model.get(),2,&falseFlag,1))!=0 ||
           (e=ANeuralNetworksModel_setOperandValue(model.get(),3,&falseFlag,1))!=0)
            return std::string(label)+" constantsError="+std::to_string(e)+"\n";
    }else if(kind==1){
        if((e=ANeuralNetworksModel_setOperandValue(model.get(),1,&beta,2))!=0 ||
           (e=ANeuralNetworksModel_setOperandValue(model.get(),2,&axis,4))!=0)
            return std::string(label)+" constantsError="+std::to_string(e)+"\n";
    }else if(kind==2||kind==3){
        if((e=ANeuralNetworksModel_setOperandValue(model.get(),2,&activation,4))!=0)
            return std::string(label)+" constantsError="+std::to_string(e)+"\n";
    }else if(kind==4){
        if((e=ANeuralNetworksModel_setOperandValue(model.get(),1,axes,sizeof(axes)))!=0 ||
           (e=ANeuralNetworksModel_setOperandValue(model.get(),2,&keep,sizeof(keep)))!=0)
            return std::string(label)+" constantsError="+std::to_string(e)+"\n";
    }else if(kind==6){
        if((e=ANeuralNetworksModel_setOperandValue(model.get(),1,permutation,sizeof(permutation)))!=0)
            return std::string(label)+" constantsError="+std::to_string(e)+"\n";
    }else if(kind==9){
        if((e=ANeuralNetworksModel_setOperandValue(model.get(),1,reshape,sizeof(reshape)))!=0)
            return std::string(label)+" constantsError="+std::to_string(e)+"\n";
    }
    const uint32_t output={(uint32_t)(types.size()-1)};
    e=ANeuralNetworksModel_addOperation(model.get(),code,(uint32_t)opInputs.size(),
                                        opInputs.data(),1,&output);
    if(e!=ANEURALNETWORKS_NO_ERROR)return std::string(label)+" addOperationError="+std::to_string(e)+"\n";
    e=ANeuralNetworksModel_identifyInputsAndOutputs(model.get(),
        (uint32_t)ioInputs.size(),ioInputs.data(),1,&output);
    if(e!=ANEURALNETWORKS_NO_ERROR)return std::string(label)+" identifyError="+std::to_string(e)+"\n";
    e=ANeuralNetworksModel_finish(model.get());
    if(e!=ANEURALNETWORKS_NO_ERROR)return std::string(label)+" finishError="+std::to_string(e)+"\n";
    const ANeuralNetworksDevice* devs[1]={dev};
    bool supported=false;
    e=ANeuralNetworksModel_getSupportedOperationsForDevices(model.get(),devs,1,&supported);
    if(e!=ANEURALNETWORKS_NO_ERROR)return std::string(label)+" queryError="+std::to_string(e)+"\n";
    if(!supported)return std::string(label)+" supported=NO\n";
    // Confirm actual compilation, not just the declared op support mask.
    ANeuralNetworksCompilation* rawCompiler=nullptr;
    e=ANeuralNetworksCompilation_createForDevices(model.get(),devs,1,&rawCompiler);
    if(e!=ANEURALNETWORKS_NO_ERROR)
        return std::string(label)+" supported=YES compileCreateError="+std::to_string(e)+"\n";
    Scope<ANeuralNetworksCompilation,ANeuralNetworksCompilation_free> compiler(rawCompiler,ANeuralNetworksCompilation_free);
    e=ANeuralNetworksCompilation_finish(compiler.get());
    return std::string(label)+" supported=YES compile="+(e==0?"OK":"ERROR_"+std::to_string(e))+"\n";
}

}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_card_anima_MainActivity_probeNpuNative(JNIEnv* env,jobject){
    std::ostringstream out;
    out<<"Anima Pocket v0.5.1 NPU operator support + comparable projection benchmark (no model files)\n";
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
            out<<"\n=== OPERATOR COMPATIBILITY "<<d.second<<" ===\n";
            for(int op: {0,1,2,3,4,5,6,7,8,9}){
                out<<probeOperator(d.first,op);
            }
            out<<"=== FP16 PROJECTIONS "<<d.second<<" ===\n";
            const std::string small=bench(d.first,d.second,64,1024,1024);
            out<<small;
            const bool smallSuccess=small.find("RESULT=STRICT_HARDWARE_MATMUL_SUCCESS")!=std::string::npos;
            if(smallSuccess && (d.second.find("mdla")!=std::string::npos ||
                                d.second.find("neuron")!=std::string::npos)){
                out<<bench(d.first,d.second,128,2048,2048);
            }
            success=success||smallSuccess;
        }
        if(!success)out<<"RESULT=NO_SUCCESSFUL_STRICT_ACCELERATOR_RUN\n";
    }
    out<<"This does not yet execute Anima transformer blocks on NPU.\n";
    return env->NewStringUTF(out.str().c_str());
}
