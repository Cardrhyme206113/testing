#include <jni.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstddef>
#include <string>
#include <vector>
#include "archive_reader.h"
#include "voxel_world.h"

namespace {
VoxelWorld world;
GLuint shader=0,vao=0,vertexBuffer=0,texture=0;
GLint uEye=-1,uLook=-1,uAspect=-1,uFog=-1;
size_t opaqueCount=0,waterCount=0;
int screenW=1,screenH=1;
float previousFrame=0;
const char* vert=R"(#version 300 es
precision highp float;
layout(location=0) in vec3 position;
layout(location=1) in vec2 uv;
layout(location=2) in vec3 tint;
uniform vec3 cameraEye;
uniform vec2 cameraAngles;
uniform float aspect;
out vec2 vUV;
out vec3 vTint;
out float vDistance;
void main(){
    float yaw=cameraAngles.x,pitch=cameraAngles.y;
    float sy=sin(yaw),cy=cos(yaw),sp=sin(pitch),cp=cos(pitch);
    vec3 right=vec3(cy,0.0,-sy);
    vec3 up=vec3(-sy*sp,cp,-cy*sp);
    vec3 forward=vec3(sy*cp,sp,cy*cp);
    vec3 rel=position-cameraEye;
    float x=dot(rel,right),y=dot(rel,up),z=dot(rel,forward);
    float nearZ=0.05,farZ=170.0,f=1.73;
    gl_Position=vec4(x*f/aspect,y*f,z*(farZ+nearZ)/(farZ-nearZ)-2.0*farZ*nearZ/(farZ-nearZ),z);
    vUV=uv; vTint=tint;vDistance=length(rel);
}
)";
const char* frag=R"(#version 300 es
precision mediump float;
in vec2 vUV;
in vec3 vTint;
in float vDistance;
uniform sampler2D diffuse;
out vec4 color;
void main() {
    vec4 base=texture(diffuse,vUV);
    if(base.a < .2) discard;
    vec3 rgb=base.rgb*vTint;
    float fog=clamp((vDistance-57.0)/79.0,0.0,1.0);
    rgb=mix(rgb,vec3(.57,.75,.96),fog);
    color=vec4(rgb,1.0);
}
)";
GLuint compile(GLenum type,const char* source){
    GLuint id=glCreateShader(type);
    glShaderSource(id,1,&source,nullptr);
    glCompileShader(id);
    GLint ok=0;glGetShaderiv(id,GL_COMPILE_STATUS,&ok);
    if(!ok){
        char log[1200]={};glGetShaderInfoLog(id,sizeof(log),nullptr,log);
        __android_log_print(ANDROID_LOG_ERROR,"LCENative","Shader error: %s",log);
        glDeleteShader(id);return 0;
    }
    return id;
}
void uploadTex(const uint8_t* pixel,int w,int h){
    if(!texture)glGenTextures(1,&texture);
    glBindTexture(GL_TEXTURE_2D,texture);
    glPixelStorei(GL_UNPACK_ALIGNMENT,1);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST_MIPMAP_NEAREST);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    glGenerateMipmap(GL_TEXTURE_2D);
}
void fallbackTex(){
    constexpr int W=256;
    std::vector<uint8_t> pixels(W*W*4);
    for(int y=0;y<W;++y)for(int x=0;x<W;++x){
        const int tile=(y/16)*16+(x/16);
        const int small=((x*7+y*11+x*y)%23);
        uint8_t r=170,g=165,b=160;
        if(tile==0||tile==3){r=135;g=160;b=95;}
        if(tile==2){r=114;g=82;b=60;}
        if(tile==4||tile==20||tile==21){r=155;g=118;b=77;}
        if(tile==18){r=214;g=202;b=145;}
        if(tile==68){r=86;g=144;b=60;}
        if(tile==14){r=55;g=100;b=180;}
        int o=(y*W+x)*4;
        pixels[o]=uint8_t(std::max(0,std::min(255,int(r)+small-12)));
        pixels[o+1]=uint8_t(std::max(0,std::min(255,int(g)+small-12)));
        pixels[o+2]=uint8_t(std::max(0,std::min(255,int(b)+small-12)));
        pixels[o+3]=255;
    }
    uploadTex(pixels.data(),W,W);
}
void rebuildIfDirty(){
    if(!world.dirty)return;
    std::vector<VoxelVertex> opaque,water;
    world.buildMesh(opaque,water);
    opaqueCount=opaque.size();waterCount=water.size();
    opaque.insert(opaque.end(),water.begin(),water.end());
    glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);
    glBufferData(GL_ARRAY_BUFFER,GLsizeiptr(opaque.size()*sizeof(VoxelVertex)),opaque.data(),GL_DYNAMIC_DRAW);
    glBindVertexArray(0);world.dirty=false;
    __android_log_print(ANDROID_LOG_INFO,"LCENative","World meshed: %zu opaque and %zu water vertices",opaqueCount,waterCount);
}
}
extern "C" JNIEXPORT void JNICALL Java_dev_card_lceandroid_MainActivity_nativeInit(JNIEnv*,jclass){
    shader=vao=vertexBuffer=texture=0;
    GLuint v=compile(GL_VERTEX_SHADER,vert),f=compile(GL_FRAGMENT_SHADER,frag);
    if(!v||!f)return;
    shader=glCreateProgram();glAttachShader(shader,v);glAttachShader(shader,f);
    glLinkProgram(shader);glDeleteShader(v);glDeleteShader(f);
    GLint ok=0;glGetProgramiv(shader,GL_LINK_STATUS,&ok);
    if(!ok){glDeleteProgram(shader);shader=0;return;}
    uEye=glGetUniformLocation(shader,"cameraEye");
    uLook=glGetUniformLocation(shader,"cameraAngles");
    uAspect=glGetUniformLocation(shader,"aspect");
    uFog=glGetUniformLocation(shader,"diffuse");
    glGenVertexArrays(1,&vao);glGenBuffers(1,&vertexBuffer);
    glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);
    glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,sizeof(VoxelVertex),(void*)offsetof(VoxelVertex,x));
    glVertexAttribPointer(1,2,GL_FLOAT,GL_FALSE,sizeof(VoxelVertex),(void*)offsetof(VoxelVertex,u));
    glVertexAttribPointer(2,3,GL_FLOAT,GL_FALSE,sizeof(VoxelVertex),(void*)offsetof(VoxelVertex,r));
    glEnableVertexAttribArray(0);glEnableVertexAttribArray(1);glEnableVertexAttribArray(2);
    glBindVertexArray(0);
    glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDisable(GL_CULL_FACE);
    fallbackTex();world.dirty=true;previousFrame=0;
}
extern "C" JNIEXPORT void JNICALL Java_dev_card_lceandroid_MainActivity_nativeResize(JNIEnv*,jclass,jint w,jint h){
    screenW=std::max(1,int(w));screenH=std::max(1,int(h));glViewport(0,0,screenW,screenH);
}
extern "C" JNIEXPORT void JNICALL Java_dev_card_lceandroid_MainActivity_nativeFrame(JNIEnv*,jclass,jfloat seconds){
    const float dt=previousFrame>0?std::max(0.f,std::min(.050f,float(seconds)-previousFrame)):1.f/60.f;
    previousFrame=seconds;
    world.tick(dt);
    glViewport(0,0,screenW,screenH);
    glClearColor(.57f,.75f,.96f,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    if(!shader||!vao)return;
    rebuildIfDirty();
    glUseProgram(shader);
    auto e=world.eye();glUniform3f(uEye,e[0],e[1],e[2]);
    glUniform2f(uLook,world.yaw,world.pitch);
    glUniform1f(uAspect,float(screenW)/float(screenH));
    glUniform1i(uFog,0);
    glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture);
    glBindVertexArray(vao);
    glDrawArrays(GL_TRIANGLES,0,GLsizei(opaqueCount));
    glDrawArrays(GL_TRIANGLES,GLint(opaqueCount),GLsizei(waterCount));
    glBindVertexArray(0);
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_card_lceandroid_MainActivity_nativeUploadAtlas(JNIEnv* env,jclass,jintArray pixels,jint w,jint h) {
    if(w<1||h<1||w>4096||h>4096||env->GetArrayLength(pixels)!=jlong(w)*h)return JNI_FALSE;
    jint* ptr=env->GetIntArrayElements(pixels,nullptr);
    if(!ptr)return JNI_FALSE;
    std::vector<uint8_t> rgba(size_t(w)*h*4);
    for(size_t i=0;i<size_t(w)*h;++i){
        const uint32_t v=uint32_t(ptr[i]);
        rgba[i*4+0]=uint8_t(v>>16);rgba[i*4+1]=uint8_t(v>>8);
        rgba[i*4+2]=uint8_t(v);rgba[i*4+3]=uint8_t(v>>24);
    }
    env->ReleaseIntArrayElements(pixels,ptr,JNI_ABORT);
    // Android supplies pixels top-first; invert rows so GL texture V points upward.
    std::vector<uint8_t> flipped(rgba.size());const size_t row=size_t(w)*4;
    for(int y=0;y<h;++y)
       std::copy_n(rgba.data()+size_t(y)*row,row,flipped.data()+size_t(h-1-y)*row);
    uploadTex(flipped.data(),w,h);
    return JNI_TRUE;
}
extern "C" JNIEXPORT void JNICALL Java_dev_card_lceandroid_MainActivity_nativeMove(JNIEnv*,jclass,jfloat f,jfloat s){world.setMove(f,s);}
extern "C" JNIEXPORT void JNICALL Java_dev_card_lceandroid_MainActivity_nativeLook(JNIEnv*,jclass,jfloat dx,jfloat dy){world.look(dx,dy);}
extern "C" JNIEXPORT void JNICALL Java_dev_card_lceandroid_MainActivity_nativeJump(JNIEnv*,jclass){world.jump();}
extern "C" JNIEXPORT void JNICALL Java_dev_card_lceandroid_MainActivity_nativeInteract(JNIEnv*,jclass,jboolean place){world.interact(place);}
extern "C" JNIEXPORT void JNICALL Java_dev_card_lceandroid_MainActivity_nativeSelect(JNIEnv*,jclass,jint index){world.select(index);}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_card_lceandroid_MainActivity_nativeSave(JNIEnv*e,jclass,jstring path){
    const char* c=e->GetStringUTFChars(path,nullptr);if(!c)return JNI_FALSE;
    bool ok=world.save(c);e->ReleaseStringUTFChars(path,c);return ok;
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_card_lceandroid_MainActivity_nativeLoad(JNIEnv*e,jclass,jstring path){
    const char* c=e->GetStringUTFChars(path,nullptr);if(!c)return JNI_FALSE;
    bool ok=world.load(c);e->ReleaseStringUTFChars(path,c);return ok;
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_card_lceandroid_MainActivity_nativeInspectArchive(JNIEnv*env,jclass,jstring file){
    const char* ptr=env->GetStringUTFChars(file,nullptr);
    ArchiveInfo info=inspectArchive(ptr?ptr:"");
    if(ptr)env->ReleaseStringUTFChars(file,ptr);
    std::string answer=info.valid?"Original archive: "+std::to_string(info.files)+" files, "+std::to_string(info.swfs)+" SWFs":
       "Archive error: "+info.error;
    return env->NewStringUTF(answer.c_str());
}
