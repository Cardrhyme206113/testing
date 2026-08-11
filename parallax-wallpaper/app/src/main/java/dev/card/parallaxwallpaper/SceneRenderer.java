package dev.card.parallaxwallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.*;
import com.github.luben.zstd.Zstd;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.*;

public final class SceneRenderer {
    public static final int MIN_FOV=35, MAX_FOV=120;
    public static final float MIN_ZOOM=1.00f, MAX_ZOOM=1.60f;
    public static final String SOURCE_FOV_KEY="source_fov_override";
    public static final String SCREEN_FOV_KEY="screen_fov_override";
    public static final String ZOOM_KEY="view_zoom";

    private final Context context;
    private final MotionController motion;
    private final SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    private int program, bgProgram, vbo;
    private int aPos, aUv, uProj, uCamera, uTilt, uTex;
    private int bgScreenAspect, bgTexAspect, bgTex, bgShift, bgZoom;
    private int[] textures = new int[0];
    private int[] triangleCounts = new int[0];
    private int[] drawLayerOrder = new int[0];
    private float sceneFov = 70f, maxParallax = .32f, textureAspect = 1.6f;
    private volatile float sourceFov=70f, screenFov=70f, extraZoom=1f;
    private int centerTextureLayer = 0;
    private int fallbackTexture = 0;
    private int width=1,height=1;
    private boolean ready=false;

    public SceneRenderer(Context c, MotionController motion) {
        this.context=c.getApplicationContext();
        this.motion=motion;
        this.prefs=context.getSharedPreferences(PackStore.PREFS,0);
    }

    public void surfaceCreated() {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glClearColor(0,0,0,1);
        program = link(VS, FS);
        aPos=GLES30.glGetAttribLocation(program,"aPos"); aUv=GLES30.glGetAttribLocation(program,"aUv");
        uProj=GLES30.glGetUniformLocation(program,"uProj");
        uCamera=GLES30.glGetUniformLocation(program,"uCamera");
        uTilt=GLES30.glGetUniformLocation(program,"uTilt");
        uTex=GLES30.glGetUniformLocation(program,"uTex");
        bgProgram=link(BG_VS,BG_FS);
        bgScreenAspect=GLES30.glGetUniformLocation(bgProgram,"uScreenAspect");
        bgTexAspect=GLES30.glGetUniformLocation(bgProgram,"uTexAspect");
        bgTex=GLES30.glGetUniformLocation(bgProgram,"uTex");
        bgShift=GLES30.glGetUniformLocation(bgProgram,"uShift");
        bgZoom=GLES30.glGetUniformLocation(bgProgram,"uZoom");
        try {
            loadCurrent();
            refreshViewSettings();
            prefListener=(p,key)->{
                if(SOURCE_FOV_KEY.equals(key)||SCREEN_FOV_KEY.equals(key)||ZOOM_KEY.equals(key)) refreshViewSettings();
            };
            prefs.registerOnSharedPreferenceChangeListener(prefListener);
            prefs.edit().putString("renderer_status", ready ? "3D mesh active • sensor: "+motion.sensorName() : "center image only").apply();
        } catch(Throwable t) {
            t.printStackTrace();
            ready=false;
            prefs.edit().putString("renderer_status", "3D mesh failed: "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage())).apply();
        }
    }

    private void refreshViewSettings(){
        float src=clamp(prefs.getFloat(SOURCE_FOV_KEY,sceneFov),MIN_FOV,MAX_FOV);
        float scr=clamp(prefs.getFloat(SCREEN_FOV_KEY,src),MIN_FOV,MAX_FOV);
        float z=clamp(prefs.getFloat(ZOOM_KEY,1f),MIN_ZOOM,MAX_ZOOM);
        sourceFov=src;
        screenFov=scr;
        extraZoom=z;
    }

    public void surfaceChanged(int w,int h){
        width=Math.max(1,w);
        height=Math.max(1,h);
        GLES30.glViewport(0,0,width,height);
    }

    public void drawFrame() {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);

        float mx=motion.x(), my=motion.y();
        boolean landscape=width>height;

        // Screen FOV controls the actual virtual camera. Zoom is applied optically using
        // tan(FOV/2), so extra zoom naturally increases screen-space parallax speed.
        float orientationZoom=landscape?1.12f:1f;
        float totalZoom=extraZoom*orientationZoom;
        float renderFov=effectiveFov(screenFov,totalZoom);

        if(fallbackTexture!=0){
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glUseProgram(bgProgram);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,fallbackTexture);
            GLES30.glUniform1i(bgTex,0);
            GLES30.glUniform1f(bgScreenAspect,(float)width/height);
            GLES30.glUniform1f(bgTexAspect,textureAspect);

            // The beauty capture was authored at sourceFov. Crop it by the physically matching
            // focal-length ratio whenever the output camera is narrower / more zoomed.
            float sourceToView=(float)(Math.tan(Math.toRadians(sourceFov)*0.5)/Math.tan(Math.toRadians(renderFov)*0.5));
            float fallbackCrop=Math.max(1.0f,sourceToView)*1.015f;
            GLES30.glUniform1f(bgZoom,fallbackCrop);
            float bgMotion=(landscape?0.045f:0.035f)*totalZoom;
            GLES30.glUniform2f(bgShift,-mx*bgMotion,my*bgMotion);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
        }
        if(!ready) return;

        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        float[] proj=new float[16];
        Matrix.perspectiveM(proj,0,renderFov,(float)width/height,.03f,2048f);
        GLES30.glUseProgram(program);
        GLES30.glUniformMatrix4fv(uProj,1,false,proj,0);

        // Keep world-space camera travel independent from zoom. Projection handles the speed
        // increase naturally: a narrower screen FOV / higher zoom magnifies the same translation.
        float requested=Math.max(landscape?0.64f:0.60f,maxParallax*1.90f);
        float travel=Math.min(requested,0.68f);
        GLES30.glUniform3f(uCamera,mx*travel,my*travel,0f);

        float tilt=(float)Math.toRadians(landscape?9.0:7.5);
        GLES30.glUniform2f(uTilt,mx*tilt,my*tilt);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vbo);
        int stride=7*4;
        GLES30.glEnableVertexAttribArray(aPos); GLES30.glVertexAttribPointer(aPos,3,GLES30.GL_FLOAT,false,stride,0);
        GLES30.glEnableVertexAttribArray(aUv); GLES30.glVertexAttribPointer(aUv,2,GLES30.GL_FLOAT,false,stride,3*4);
        GLES30.glUniform1i(uTex,0); GLES30.glActiveTexture(GLES30.GL_TEXTURE0);

        for(int k=0;k<drawLayerOrder.length;k++){
            int layer=drawLayerOrder[k];
            if(layer<0 || layer>=triangleCounts.length || layer>=textures.length) continue;
            int first=0;
            for(int i=0;i<layer;i++) first+=triangleCounts[i]*3;
            int count=triangleCounts[layer]*3;
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[layer]);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES,first,count);
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);
    }

    public void destroy(){
        if(prefListener!=null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        if(vbo!=0)GLES30.glDeleteBuffers(1,new int[]{vbo},0);
        if(textures.length>0)GLES30.glDeleteTextures(textures.length,textures,0);
        if(program!=0)GLES30.glDeleteProgram(program);
        if(bgProgram!=0)GLES30.glDeleteProgram(bgProgram);
    }

    private void loadCurrent() throws Exception {
        File dir=PackStore.currentScene(context); if(dir==null) return;
        JSONObject p=PackStore.readPack(dir);
        sceneFov=clamp((float)p.optDouble("verticalFovDegrees",70),MIN_FOV,MAX_FOV);
        maxParallax=(float)p.optDouble("maxParallaxWorld",.32);
        textureAspect=(float)p.optDouble("textureAspect",1.6);
        centerTextureLayer=p.optInt("centerTextureLayer",0);

        JSONArray tc=p.getJSONArray("trianglesPerSource");
        triangleCounts=new int[tc.length()]; int tris=0;
        for(int i=0;i<tc.length();i++){triangleCounts[i]=tc.getInt(i);tris+=triangleCounts[i];}
        JSONArray order=p.optJSONArray("drawLayerOrder");
        drawLayerOrder=new int[order!=null?order.length():triangleCounts.length];
        for(int i=0;i<drawLayerOrder.length;i++) drawLayerOrder[i]=order!=null?order.getInt(i):i;

        JSONArray tx=p.getJSONArray("textures");
        textures=new int[tx.length()];
        if(textures.length==0) throw new IOException("Scene contains no beauty textures");
        centerTextureLayer=Math.max(0,Math.min(centerTextureLayer,textures.length-1));
        GLES30.glGenTextures(textures.length,textures,0);
        loadTexture(textures[centerTextureLayer], new File(dir,tx.getString(centerTextureLayer)));
        fallbackTexture=textures[centerTextureLayer];
        for(int i=0;i<textures.length;i++){
            if(i==centerTextureLayer) continue;
            loadTexture(textures[i], new File(dir,tx.getString(i)));
        }

        int rawBytes=p.getInt("rawMeshBytes");
        byte[] compressed=java.nio.file.Files.readAllBytes(new File(dir,"mesh.bin.zst").toPath());
        byte[] raw=Zstd.decompress(compressed,rawBytes);
        if(raw.length!=rawBytes) throw new IOException("Mesh decompression size mismatch");
        ByteBuffer mesh=ByteBuffer.allocateDirect(raw.length).order(ByteOrder.LITTLE_ENDIAN);
        mesh.put(raw).flip(); raw=null; compressed=null;
        int[] ids=new int[1]; GLES30.glGenBuffers(1,ids,0); vbo=ids[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,mesh.remaining(),mesh,GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);
        int err=GLES30.glGetError();
        if(err!=GLES30.GL_NO_ERROR) throw new IOException("GPU mesh upload failed, GL error 0x"+Integer.toHexString(err));
        ready=true;
    }

    private static float effectiveFov(float fov,float zoom){
        double half=Math.toRadians(fov)*0.5;
        float out=(float)Math.toDegrees(2.0*Math.atan(Math.tan(half)/Math.max(0.01,zoom)));
        return clamp(out,18f,MAX_FOV);
    }

    private static void loadTexture(int id, File file) throws IOException {
        Bitmap b=BitmapFactory.decodeFile(file.getAbsolutePath());
        if(b==null)throw new IOException("Could not decode texture: "+file.getName());
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,id);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D,0,b,0);
        int err=GLES30.glGetError();
        b.recycle();
        if(err!=GLES30.GL_NO_ERROR) throw new IOException("GPU texture upload failed, GL error 0x"+Integer.toHexString(err));
    }

    private static int link(String vs,String fs){
        int v=compile(GLES30.GL_VERTEX_SHADER,vs),f=compile(GLES30.GL_FRAGMENT_SHADER,fs),p=GLES30.glCreateProgram();
        GLES30.glAttachShader(p,v);GLES30.glAttachShader(p,f);GLES30.glLinkProgram(p);
        int[] ok=new int[1];GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0);
        if(ok[0]==0)throw new RuntimeException(GLES30.glGetProgramInfoLog(p));
        GLES30.glDeleteShader(v);GLES30.glDeleteShader(f);return p;
    }
    private static int compile(int type,String s){
        int sh=GLES30.glCreateShader(type);GLES30.glShaderSource(sh,s);GLES30.glCompileShader(sh);
        int[] ok=new int[1];GLES30.glGetShaderiv(sh,GLES30.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0)throw new RuntimeException(GLES30.glGetShaderInfoLog(sh));return sh;
    }
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}

    private static final String VS=
            "#version 300 es\nprecision highp float;\n"+
            "in vec3 aPos; in vec2 aUv; uniform mat4 uProj; uniform vec3 uCamera; uniform vec2 uTilt; out vec2 vUv;\n"+
            "void main(){\n"+
            " vec3 p=aPos-uCamera;\n"+
            " float yaw=-uTilt.x, pitch=-uTilt.y;\n"+
            " float cy=cos(yaw), sy=sin(yaw);\n"+
            " p=vec3(cy*p.x+sy*p.z,p.y,-sy*p.x+cy*p.z);\n"+
            " float cp=cos(pitch), sp=sin(pitch);\n"+
            " p=vec3(p.x,cp*p.y-sp*p.z,sp*p.y+cp*p.z);\n"+
            " gl_Position=uProj*vec4(p.x,p.y,-p.z,1.0); vUv=vec2(aUv.x,1.0-aUv.y);\n"+
            "}";
    private static final String FS=
            "#version 300 es\nprecision mediump float; in vec2 vUv; uniform sampler2D uTex; out vec4 frag;\n"+
            "void main(){ frag=texture(uTex,vUv); }";
    private static final String BG_VS=
            "#version 300 es\nout vec2 ndc; void main(){ vec2 p=gl_VertexID==0?vec2(-1.0,-1.0):(gl_VertexID==1?vec2(3.0,-1.0):vec2(-1.0,3.0)); ndc=p; gl_Position=vec4(p,0.999,1.0); }";
    private static final String BG_FS=
            "#version 300 es\nprecision mediump float; in vec2 ndc; uniform sampler2D uTex; uniform float uScreenAspect,uTexAspect,uZoom; uniform vec2 uShift; out vec4 frag; " +
            "void main(){ vec2 d=vec2(0.5*ndc.x*(uScreenAspect/uTexAspect),-0.5*ndc.y)/max(uZoom,0.001); vec2 uv=vec2(0.5)+d+uShift; frag=texture(uTex,clamp(uv,0.0,1.0)); }";
}
