#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <cstdio>
#include "stable-diffusion.h"

namespace {
std::mutex mutex;
std::atomic<bool> cancelled(false);
sd_ctx_t* active=nullptr;
JavaVM* vm=nullptr;
jobject activity=nullptr;

std::string str(JNIEnv* env,jstring s){
    if(!s)return {};
    const char* chars=env->GetStringUTFChars(s,nullptr);
    if(!chars)return {};
    std::string output(chars);
    env->ReleaseStringUTFChars(s,chars);return output;
}
void notify(const std::string& msg){
    if(!vm||!activity)return;
    JNIEnv* env=nullptr;bool attached=false;
    if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK){
        if(vm->AttachCurrentThread(&env,nullptr)!=JNI_OK)return;
        attached=true;
    }
    jclass cls=env->GetObjectClass(activity);
    jmethodID cb=env->GetMethodID(cls,"onNativeStatus","(Ljava/lang/String;)V");
    if(cb){jstring s=env->NewStringUTF(msg.c_str());env->CallVoidMethod(activity,cb,s);env->DeleteLocalRef(s);
        if(env->ExceptionCheck())env->ExceptionClear();}
    env->DeleteLocalRef(cls);
    if(attached)vm->DetachCurrentThread();
}
void logs(sd_log_level_t level,const char* msg,void*){
    if (!msg) return;
    __android_log_print(level>=SD_LOG_ERROR?ANDROID_LOG_ERROR:ANDROID_LOG_INFO,
        "AnimaCore","%s",msg);
    // The phone user may have no ADB/Logcat access. Send the engine log
    // to the app itself, preserving the complete diagnostic trail.
    if(!vm || !activity) return;
    JNIEnv* env=nullptr;
    bool attached=false;
    if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK){
        if(vm->AttachCurrentThread(&env,nullptr)!=JNI_OK) return;
        attached=true;
    }
    jclass cls=env->GetObjectClass(activity);
    jmethodID cb=env->GetMethodID(cls,"onNativeLog","(ILjava/lang/String;)V");
    if(cb){
        jstring line=env->NewStringUTF(msg);
        if(line){ env->CallVoidMethod(activity,cb,static_cast<jint>(level),line);env->DeleteLocalRef(line); }
        if(env->ExceptionCheck())env->ExceptionClear();
    }
    env->DeleteLocalRef(cls);
    if(attached)vm->DetachCurrentThread();
}
void progress(int step,int total,float elapsed,void*){
    notify("Sampling "+std::to_string(step)+"/"+std::to_string(total)+" · "+std::to_string((int)elapsed)+"s");
}
void failure(JNIEnv* env,const char* msg){
    jclass ex=env->FindClass("java/lang/RuntimeException");
    if(ex)env->ThrowNew(ex,msg);
}
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_card_anima_MainActivity_devices(JNIEnv* env,jobject){
    size_t len=sd_list_devices(nullptr,0);std::vector<char> data(len+1,0);
    sd_list_devices(data.data(),data.size());return env->NewStringUTF(data.data());
}

extern "C" JNIEXPORT void JNICALL Java_dev_card_anima_MainActivity_cancelNative(JNIEnv*,jobject){
    cancelled=true;
    std::lock_guard<std::mutex> guard(mutex);
    if(active)sd_cancel_generation(active,SD_CANCEL_ALL);
}

extern "C" JNIEXPORT jintArray JNICALL Java_dev_card_anima_MainActivity_generateNative(
    JNIEnv* env,jobject caller,jstring modelPath,jstring encoderPath,jstring decoderPath,
    jboolean useTae,jstring promptString,jint width,jint height,jint steps,jlong seed,jfloat cfg,
    jstring backendString,jboolean flash,jboolean diskEncoder){
    std::string model=str(env,modelPath),llm=str(env,encoderPath),decoder=str(env,decoderPath);
    std::string prompt=str(env,promptString),backend=str(env,backendString);
    if(model.empty()||llm.empty()||decoder.empty()||prompt.empty()||width<128||height<128||
        width>512||height>512||width%16||height%16||steps<1||steps>32){
        failure(env,"Invalid model selection or generation settings");return nullptr;
    }
    env->GetJavaVM(&vm);
    activity=env->NewGlobalRef(caller);
    cancelled=false;
    sd_set_log_callback(logs,nullptr);
    sd_set_progress_callback(progress,nullptr);
    const bool gpu=(backend=="vulkan-auto" || backend=="vulkan-1.5");
    if(!gpu && backend!="cpu"){
        env->DeleteGlobalRef(activity);activity=nullptr;
        failure(env,"Unknown backend safety profile");return nullptr;
    }
    std::string runtime=gpu?"diffusion=vulkan0,te=cpu,vae=cpu":"cpu";
    // `disk` is an upstream, file-backed parameter placement, not a GPU
    // allocation limit. CPU execution still needs temporary activation buffers.
    // In the default low-RAM mode, all weights are lazily paged from storage.
    std::string residency=diskEncoder
        ?"diffusion=disk,te=disk,vae=disk"
        :"diffusion=disk,te=cpu,vae=disk";
    // No explicit Vulkan max_vram for auto mode: engine manages available
    // shared memory. This is NOT a hard physical RAM cap or OOM guarantee.
    // The legacy 5 GiB Android free-RAM admission gate is also removed.
    std::string vram=backend=="vulkan-1.5"?"vulkan0=1.5":"";
    notify(backend=="vulkan-auto"?"Preparing automatic Vulkan (experimental)...":
        gpu?"Preparing budgeted Vulkan (experimental)...":"Preparing CPU-only...");
    logs(SD_LOG_INFO,(std::string("Profile=")+backend+" runtime="+runtime+
        " weights="+residency+" maxVRAM="+(gpu?(vram.empty()?"auto":vram):"none")+
        " encoderDisk="+(diskEncoder?"true":"false")+
        " prefetch=off eager=off threads=2").c_str(),nullptr);
    sd_ctx_params_t settings{};
    sd_ctx_params_init(&settings);
    settings.diffusion_model_path=model.c_str();
    settings.llm_path=llm.c_str();
    if(useTae)settings.taesd_path=decoder.c_str();
    else settings.vae_path=decoder.c_str();
    settings.backend=runtime.c_str();
    settings.params_backend=residency.c_str();
    settings.max_vram=gpu&&!vram.empty()?vram.c_str():nullptr;
    settings.auto_fit=false;
    settings.enable_mmap=true;
    settings.disable_prefetch=true;
    settings.eager_load=false;
    settings.conditioning_cache_size=0;
    // Preserve segmented graph execution to reduce transient allocation spikes.
    settings.disable_segmented_compute=false;
    // Avoid simultaneous conditional/unconditional inference activation buffers.
    settings.batched_cfg=false;
    settings.diffusion_flash_attn=flash;
    settings.n_threads=2;
    sd_ctx_t* context=new_sd_ctx(&settings);
    if(!context){
        env->DeleteGlobalRef(activity);activity=nullptr;
        failure(env,"Could not initialize model. See engine diagnostics below.");return nullptr;
    }
    {
        std::lock_guard<std::mutex> guard(mutex);
        active=context;
        if(cancelled)sd_cancel_generation(context,SD_CANCEL_ALL);
    }
    sd_img_gen_params_t gen{};
    sd_img_gen_params_init(&gen);
    gen.prompt=prompt.c_str();
    gen.width=width;gen.height=height;
    gen.seed=(int64_t)seed;gen.batch_count=1;
    gen.sample_params.sample_steps=steps;
    gen.sample_params.sample_method=EULER_SAMPLE_METHOD;
    gen.sample_params.scheduler=SGM_UNIFORM_SCHEDULER;
    gen.sample_params.guidance.txt_cfg=cfg;
    // The full Wan/Qwen VAE can have large decode activations. TAE is already
    // compact and should not be tiled.
    if(!useTae){
        gen.vae_tiling_params.enabled=true;
        gen.vae_tiling_params.tile_size_w=256;
        gen.vae_tiling_params.tile_size_h=256;
        gen.vae_tiling_params.target_overlap=0.1f;
    }
    sd_image_t* results=nullptr;int count=0;
    notify("Generating...");
    bool ok=!cancelled&&generate_image(context,&gen,&results,&count);
    jintArray output=nullptr;
    if(ok&&results&&count>0&&results[0].data&&results[0].width==(uint32_t)width
        &&results[0].height==(uint32_t)height&&results[0].channel>=3){
        sd_image_t& img=results[0];
        std::vector<jint> argb((size_t)width*height);
        for(size_t i=0;i<argb.size();++i){
            size_t n=i*img.channel;
            unsigned r=img.data[n],g=img.data[n+1],b=img.data[n+2],a=img.channel>3?img.data[n+3]:255;
            argb[i]=(jint)((a<<24)|(r<<16)|(g<<8)|b);
        }
        output=env->NewIntArray((jsize)argb.size());
        if(output)env->SetIntArrayRegion(output,0,(jsize)argb.size(),argb.data());
    }
    if(results)free_sd_images(results,count);
    {
        std::lock_guard<std::mutex> guard(mutex);
        active=nullptr;
    }
    free_sd_ctx(context);
    env->DeleteGlobalRef(activity);activity=nullptr;
    if(!output&&!env->ExceptionCheck())failure(env,cancelled?"Generation cancelled":"Generation failed. See engine diagnostics below.");
    return output;
}
