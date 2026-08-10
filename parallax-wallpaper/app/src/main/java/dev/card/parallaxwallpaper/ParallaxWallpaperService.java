package dev.card.parallaxwallpaper;

import android.opengl.*;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public class ParallaxWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() { return new ParallaxEngine(); }

    private final class ParallaxEngine extends Engine {
        private MotionController motion;
        private RenderThread thread;
        private boolean visible;

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            motion = new MotionController(ParallaxWallpaperService.this);
        }
        @Override public void onVisibilityChanged(boolean v) {
            visible=v;
            if(v){ motion.start(); startThread(); } else { motion.stop(); stopThread(); }
        }
        @Override public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            // Android may report the wallpaper visible before its Surface exists.
            // Retry startup here so that lifecycle ordering cannot leave us permanently black.
            startThread();
        }
        @Override public void onSurfaceChanged(SurfaceHolder holder,int format,int width,int height) {
            super.onSurfaceChanged(holder,format,width,height);
            if(thread!=null) thread.setSize(width,height);
            else startThread();
        }
        @Override public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            startThread();
        }
        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder); stopThread();
        }
        @Override public void onDestroy() { motion.stop(); stopThread(); super.onDestroy(); }

        private synchronized void startThread(){
            if(thread!=null||!visible||getSurfaceHolder().getSurface()==null||!getSurfaceHolder().getSurface().isValid())return;
            thread=new RenderThread(getSurfaceHolder(),motion); thread.start();
        }
        private synchronized void stopThread(){ if(thread!=null){thread.shutdown();try{thread.join(1200);}catch(InterruptedException ignored){}thread=null;} }
    }

    private final class RenderThread extends Thread {
        private final SurfaceHolder holder; private final MotionController motion;
        private volatile boolean running=true; private volatile int w=1,h=1;
        private EGLDisplay display; private EGLContext context; private EGLSurface surface;

        RenderThread(SurfaceHolder h,MotionController m){super("ParallaxWallpaperGL");holder=h;motion=m;}
        void setSize(int width,int height){w=Math.max(1,width);h=Math.max(1,height);}
        void shutdown(){running=false;interrupt();}

        @Override public void run(){
            SceneRenderer renderer=null;
            try{
                initEgl();
                int[] size=new int[2]; EGL14.eglQuerySurface(display,surface,EGL14.EGL_WIDTH,size,0); EGL14.eglQuerySurface(display,surface,EGL14.EGL_HEIGHT,size,1); w=size[0];h=size[1];
                renderer=new SceneRenderer(ParallaxWallpaperService.this,motion); renderer.surfaceCreated(); renderer.surfaceChanged(w,h);
                long frameNs=1_000_000_000L/60L;
                while(running){
                    long start=System.nanoTime(); renderer.surfaceChanged(w,h); renderer.drawFrame();
                    if(!EGL14.eglSwapBuffers(display,surface))break;
                    long left=frameNs-(System.nanoTime()-start); if(left>0)try{Thread.sleep(left/1_000_000L,(int)(left%1_000_000L));}catch(InterruptedException ignored){}
                }
            }catch(Throwable t){t.printStackTrace();}
            finally{if(renderer!=null)try{renderer.destroy();}catch(Throwable ignored){}releaseEgl();}
        }

        private void initEgl(){
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY); int[] ver=new int[2]; if(!EGL14.eglInitialize(display,ver,0,ver,1))throw new RuntimeException("eglInitialize failed");
            int[] attrs={EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_DEPTH_SIZE,24,EGL14.EGL_NONE};
            EGLConfig[] cfg=new EGLConfig[1]; int[] n=new int[1]; if(!EGL14.eglChooseConfig(display,attrs,0,cfg,0,1,n,0)||n[0]==0)throw new RuntimeException("No GLES3 config");
            int[] ca={EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE}; context=EGL14.eglCreateContext(display,cfg[0],EGL14.EGL_NO_CONTEXT,ca,0);
            int[] sa={EGL14.EGL_NONE}; surface=EGL14.eglCreateWindowSurface(display,cfg[0],holder,sa,0);
            if(context==EGL14.EGL_NO_CONTEXT||surface==EGL14.EGL_NO_SURFACE||!EGL14.eglMakeCurrent(display,surface,surface,context))throw new RuntimeException("Could not create EGL surface/context");
        }
        private void releaseEgl(){
            try{if(display!=null&&display!=EGL14.EGL_NO_DISPLAY){EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(surface!=null&&surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface);if(context!=null&&context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);}}catch(Throwable ignored){}
        }
    }
}
