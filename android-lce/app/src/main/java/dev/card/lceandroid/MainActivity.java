package dev.card.lceandroid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class MainActivity extends Activity {
    private static final int PICK_ARCHIVE = 100;
    static { System.loadLibrary("lceandroid"); }
    private static native void nativeInit();
    private static native void nativeResize(int width, int height);
    private static native void nativeFrame(float seconds);
    private static native boolean nativeUploadAtlas(int[] pixels,int width,int height);
    private static native void nativeMove(float forward,float sideways);
    private static native void nativeLook(float dx,float dy);
    private static native void nativeJump();
    private static native void nativeInteract(boolean place);
    private static native void nativeSelect(int slot);
    private static native boolean nativeSave(String path);
    private static native boolean nativeLoad(String path);
    private static native String nativeInspectArchive(String path);
    private static native String nativeOriginalWorldProbe();
    private GLSurfaceView surface;
    private TouchOverlay controls;
    private TextView status;
    private volatile int[] terrainPixels;
    private int terrainW=256,terrainH=256;
    private String worldPath;
    private boolean gamepadConnected=false;
    private static final long MAX_IMPORT=320L*1024*1024;
    private void send(Runnable r){surface.queueEvent(r);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
           4|2|4096|1024|512|256);
        worldPath=new File(getFilesDir(),"native_world_v02.dat").getAbsolutePath();
        surface=new GLSurfaceView(this);
        surface.setEGLContextClientVersion(3);
        surface.setPreserveEGLContextOnPause(true);
        surface.setRenderer(new GLSurfaceView.Renderer(){
            long epoch;
            public void onSurfaceCreated(GL10 gl,EGLConfig cfg){
                epoch=SystemClock.elapsedRealtimeNanos();
                nativeInit();
                nativeLoad(worldPath);
                int[] pix=terrainPixels;
                if(pix!=null)nativeUploadAtlas(pix,terrainW,terrainH);
            }
            public void onSurfaceChanged(GL10 gl,int w,int h){nativeResize(w,h);}
            public void onDrawFrame(GL10 gl){
                nativeFrame((SystemClock.elapsedRealtimeNanos()-epoch)*1e-9f);
            }
        });
        FrameLayout root=new FrameLayout(this);
        root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        controls=new TouchOverlay(this,new TouchOverlay.Controls(){
            public void move(float f,float s){send(()->nativeMove(f,s));}
            public void look(float x,float y){send(()->nativeLook(x,y));}
            public void jump(){send(MainActivity::nativeJump);}
            public void interact(boolean place){send(()->nativeInteract(place));}
            public void select(int slot){send(()->nativeSelect(slot));}
        });
        root.addView(controls,new FrameLayout.LayoutParams(-1,-1));
        status=new TextView(this);
        status.setText(nativeOriginalWorldProbe()+"\nImport original res.zip to load LCE textures");
        status.setTextSize(12);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0x880b1826);
        status.setPadding(8,5,8,5);
        FrameLayout.LayoutParams statusP=new FrameLayout.LayoutParams(-2,-2,Gravity.LEFT|Gravity.TOP);
        statusP.leftMargin=8;statusP.topMargin=8;
        root.addView(status,statusP);
        Button importButton=new Button(this);
        importButton.setAllCaps(false);
        importButton.setText("Import original assets");
        importButton.setTextSize(12);
        FrameLayout.LayoutParams importP=new FrameLayout.LayoutParams(-2,-2,Gravity.RIGHT|Gravity.TOP);
        importP.rightMargin=8;importP.topMargin=5;
        root.addView(importButton,importP);
        importButton.setOnClickListener(v->{
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent,PICK_ARCHIVE);
        });
        setContentView(root);
        new Thread(this::loadSavedImages).start();
    }
    private void loadSavedImages(){
        try{
            File terrain=new File(getFilesDir(),"lce_terrain.png");
            if(terrain.isFile())prepareAtlas(terrain);
            File gui=new File(getFilesDir(),"lce_gui.png");
            File icons=new File(getFilesDir(),"lce_icons.png");
            Bitmap a=gui.isFile()?BitmapFactory.decodeFile(gui.getAbsolutePath()):null;
            Bitmap b=icons.isFile()?BitmapFactory.decodeFile(icons.getAbsolutePath()):null;
            runOnUiThread(()->controls.setOriginalGui(a,b));
        }catch(Exception ex){display("Asset loading failed: "+ex.getMessage());}
    }
    private void prepareAtlas(File f){
        Bitmap image=BitmapFactory.decodeFile(f.getAbsolutePath());
        if(image==null)throw new IllegalStateException("Not a PNG texture atlas");
        if(image.getWidth()!=256||image.getHeight()!=256){
            throw new IllegalStateException("Expected 256×256 original LCE atlas");
        }
        int[] pixels=new int[256*256];image.getPixels(pixels,0,256,0,0,256,256);
        terrainPixels=pixels;terrainW=256;terrainH=256;
        send(()->nativeUploadAtlas(pixels,256,256));
        image.recycle();
    }
    private void display(String message){runOnUiThread(()->status.setText(message));}
    private void copyStream(InputStream in,File out,long limit)throws Exception{
        long copied=0;
        try(FileOutputStream f=new FileOutputStream(out)){
            byte[] data=new byte[131072];int n;
            while((n=in.read(data))!=-1){
                copied+=n;if(copied>limit)throw new IllegalStateException("File too large");
                f.write(data,0,n);
            }
        }
    }
    private boolean extract(ZipFile zip,String key,String dest)throws Exception{
        ZipEntry e=zip.getEntry(key);
        if(e==null||e.isDirectory()||e.getSize()>4*1024*1024)return false;
        try(InputStream in=zip.getInputStream(e)){
            copyStream(in,new File(getFilesDir(),dest),4*1024*1024);
        }
        return true;
    }
    private void importArchive(Uri uri){
        File temp=new File(getCacheDir(),"lce-import.tmp");
        try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)throw new IllegalStateException("Cannot open document");
            copyStream(in,temp,MAX_IMPORT);
            boolean zipped=false;
            try(ZipFile zip=new ZipFile(temp)){
                zipped=true;
                // Highest priority is the exact TitleUpdate block atlas supplied by user.
                boolean ok=extract(zip,"res/TitleUpdate/res/terrain.png","lce_terrain.png");
                if(!ok)ok=extract(zip,"res/1_2_2/terrain.png","lce_terrain.png");
                if(!ok)ok=extract(zip,"res/terrain.png","lce_terrain.png");
                extract(zip,"res/gui/gui.png","lce_gui.png");
                extract(zip,"res/gui/icons.png","lce_icons.png");
                if(!ok)throw new IllegalStateException("ZIP contains no original terrain.png");
            }catch(java.util.zip.ZipException notZip){
                zipped=false;
            }
            if(zipped){
                loadSavedImages();
                display("Original res.zip loaded · authentic terrain/UI textures active");
            }else {
                File arc=new File(getFilesDir(),"MediaWindows64.arc");
                try(InputStream i=new java.io.FileInputStream(temp)){copyStream(i,arc,MAX_IMPORT);}
                display(nativeInspectArchive(arc.getAbsolutePath())+" · UI archive indexed");
            }
        }catch(Exception e){display("Import failed: "+e.getMessage());}
        finally{if(temp.exists())temp.delete();}
    }
    @Override public void onActivityResult(int req,int result,Intent intent){
        super.onActivityResult(req,result,intent);
        if(req!=PICK_ARCHIVE||result!=RESULT_OK||intent==null||intent.getData()==null)return;
        Uri uri=intent.getData();display("Importing originals...");
        new Thread(()->importArchive(uri)).start();
    }
    @Override public boolean onGenericMotionEvent(MotionEvent e){
        if((e.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK
            &&e.getAction()==MotionEvent.ACTION_MOVE){
            float s=e.getAxisValue(MotionEvent.AXIS_X);
            float f=-e.getAxisValue(MotionEvent.AXIS_Y);
            float lx=e.getAxisValue(MotionEvent.AXIS_Z);
            float ly=e.getAxisValue(MotionEvent.AXIS_RZ);
            send(()->{nativeMove(f,s);nativeLook(lx*12,ly*12);});
            gamepadConnected=true;return true;
        }
        return super.onGenericMotionEvent(e);
    }
    @Override public boolean onKeyDown(int code,KeyEvent e){
        if(code==KeyEvent.KEYCODE_BUTTON_A||code==KeyEvent.KEYCODE_SPACE){
            send(MainActivity::nativeJump);return true;
        }
        if(code==KeyEvent.KEYCODE_BUTTON_R2||code==KeyEvent.KEYCODE_BUTTON_X){
            send(()->nativeInteract(false));return true;
        }
        if(code==KeyEvent.KEYCODE_BUTTON_L2||code==KeyEvent.KEYCODE_BUTTON_Y){
            send(()->nativeInteract(true));return true;
        }
        if(code>=KeyEvent.KEYCODE_1&&code<=KeyEvent.KEYCODE_9){
            int slot=code-KeyEvent.KEYCODE_1;send(()->nativeSelect(slot));return true;
        }
        return super.onKeyDown(code,e);
    }
    @Override protected void onPause(){
        if(surface!=null){send(()->nativeSave(worldPath));surface.onPause();}
        super.onPause();
    }
    @Override protected void onResume(){super.onResume();if(surface!=null)surface.onResume();}
}
