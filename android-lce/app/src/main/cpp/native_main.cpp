#include <jni.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <string>
#include "archive_reader.h"
namespace {
GLuint shaderProgram=0, vertexArray=0, vertexBuffer=0, indexBuffer=0;
GLint timeUniform=-1, aspectUniform=-1;
int w=1,h=1;
const char* vs=R"(#version 300 es
layout(location=0) in vec3 position;
layout(location=1) in vec3 color;
uniform float uTime;
uniform float uAspect;
out vec3 vColor;
void main() {
    float c=cos(uTime*0.75),s=sin(uTime*0.75);
    vec3 p=vec3(position.x*c+position.z*s,position.y,-position.x*s+position.z*c);
    float cy=cos(0.32),sy=sin(0.32);
    p=vec3(p.x,p.y*cy-p.z*sy,p.y*sy+p.z*cy);
    float d=3.5-p.z;
    gl_Position=vec4(p.x*1.8*uAspect,p.y*1.8,d-1.8,d);
    vColor=color;
}
)";
const char* fs=R"(#version 300 es
precision mediump float;
in vec3 vColor;
out vec4 fragColor;
void main(){ fragColor=vec4(vColor,1.0); }
)";
GLuint compileShader(GLenum kind,const char* body) {
  GLuint sh=glCreateShader(kind);
  glShaderSource(sh,1,&body,nullptr);
  glCompileShader(sh);
  GLint compiled=0;glGetShaderiv(sh,GL_COMPILE_STATUS,&compiled);
  if(!compiled){
    char output[1024]={};
    glGetShaderInfoLog(sh,sizeof(output),nullptr,output);
    __android_log_print(ANDROID_LOG_ERROR,"LCENative","shader failure: %s",output);
    glDeleteShader(sh);return 0;
  }
  return sh;
}
}
extern "C" JNIEXPORT void JNICALL Java_dev_card_lceandroid_MainActivity_nativeInit(JNIEnv*,jclass) {
  shaderProgram=vertexArray=vertexBuffer=indexBuffer=0;
  GLuint v=compileShader(GL_VERTEX_SHADER,vs),f=compileShader(GL_FRAGMENT_SHADER,fs);
  if(!v || !f) return;
  shaderProgram=glCreateProgram();
  glAttachShader(shaderProgram,v);glAttachShader(shaderProgram,f);
  glLinkProgram(shaderProgram);
  glDeleteShader(v);glDeleteShader(f);
  GLint linked=0;glGetProgramiv(shaderProgram,GL_LINK_STATUS,&linked);
  if(!linked){glDeleteProgram(shaderProgram);shaderProgram=0;return;}
  timeUniform=glGetUniformLocation(shaderProgram,"uTime");
  aspectUniform=glGetUniformLocation(shaderProgram,"uAspect");
  constexpr GLfloat vertices[]={
    -1,-1,-1,.3f,.6f,.9f, 1,-1,-1,.3f,.6f,.9f,
     1, 1,-1,.3f,.6f,.9f,-1, 1,-1,.3f,.6f,.9f,
    -1,-1, 1,.3f,.9f,.6f, 1,-1, 1,.3f,.9f,.6f,
     1, 1, 1,.3f,.9f,.6f,-1, 1, 1,.3f,.9f,.6f
  };
  constexpr GLushort indices[]={
    0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,
    3,7,6,3,6,2,1,2,6,1,6,5,0,4,7,0,7,3
  };
  glGenVertexArrays(1,&vertexArray);glBindVertexArray(vertexArray);
  glGenBuffers(1,&vertexBuffer);glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);
  glBufferData(GL_ARRAY_BUFFER,sizeof(vertices),vertices,GL_STATIC_DRAW);
  glGenBuffers(1,&indexBuffer);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,indexBuffer);
  glBufferData(GL_ELEMENT_ARRAY_BUFFER,sizeof(indices),indices,GL_STATIC_DRAW);
  glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,6*sizeof(GLfloat),(void*)0);
  glEnableVertexAttribArray(0);
  glVertexAttribPointer(1,3,GL_FLOAT,GL_FALSE,6*sizeof(GLfloat),(void*)(3*sizeof(GLfloat)));
  glEnableVertexAttribArray(1);
  glBindVertexArray(0);
  glEnable(GL_DEPTH_TEST);glDisable(GL_CULL_FACE);
}
extern "C" JNIEXPORT void JNICALL Java_dev_card_lceandroid_MainActivity_nativeResize(JNIEnv*,jclass,jint x,jint y) {
  w=x>0?x:1;h=y>0?y:1;glViewport(0,0,w,h);
}
extern "C" JNIEXPORT void JNICALL Java_dev_card_lceandroid_MainActivity_nativeFrame(JNIEnv*,jclass,jfloat time) {
  glViewport(0,0,w,h);
  glClearColor(.045f,.07f,.105f,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
  if(!shaderProgram||!vertexArray)return;
  glUseProgram(shaderProgram);
  glUniform1f(timeUniform,time);
  glUniform1f(aspectUniform,float(h)/float(w));
  glBindVertexArray(vertexArray);
  glDrawElements(GL_TRIANGLES,36,GL_UNSIGNED_SHORT,nullptr);
  glBindVertexArray(0);
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_card_lceandroid_MainActivity_nativeInspectArchive(JNIEnv*env,jclass,jstring file) {
  const char* ptr=env->GetStringUTFChars(file,nullptr);
  const ArchiveInfo info=inspectArchive(ptr?ptr:"");
  if(ptr)env->ReleaseStringUTFChars(file,ptr);
  const std::string answer=info.valid ?
    "Original archive indexed in ARM64 C++: "+std::to_string(info.files)+
    " files, "+std::to_string(info.swfs)+" SWFs, "+std::to_string(info.compressed)+" compressed" :
    "Archive error: "+info.error;
  return env->NewStringUTF(answer.c_str());
}
