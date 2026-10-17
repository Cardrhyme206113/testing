package dev.card.lceandroid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class MainActivity extends Activity {
    private static final int PICK_ARCHIVE = 100;
    static { System.loadLibrary("lceandroid"); }
    private static native void nativeInit();
    private static native void nativeResize(int width, int height);
    private static native void nativeFrame(float seconds);
    private static native String nativeInspectArchive(String path);

    private GLSurfaceView surface;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        surface = new GLSurfaceView(this);
        surface.setEGLContextClientVersion(3);
        surface.setPreserveEGLContextOnPause(true);
        surface.setRenderer(new GLSurfaceView.Renderer() {
            long epoch;
            public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
                epoch = SystemClock.elapsedRealtimeNanos();
                nativeInit();
            }
            public void onSurfaceChanged(GL10 gl, int w, int h) { nativeResize(w,h); }
            public void onDrawFrame(GL10 gl) {
                nativeFrame((SystemClock.elapsedRealtimeNanos()-epoch)*1e-9f);
            }
        });
        FrameLayout layout = new FrameLayout(this);
        layout.addView(surface, new FrameLayout.LayoutParams(-1,-1));
        TextView title = new TextView(this);
        title.setText("LCE / NATIVE ARM64 / GLES3 BOOTSTRAP\nGame engine not integrated yet");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setPadding(20,12,20,12);
        layout.addView(title, new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT));
        status = new TextView(this);
        status.setText("Import original MediaWindows64.arc to inspect it natively");
        status.setTextSize(13);
        status.setTextColor(Color.WHITE);
        FrameLayout.LayoutParams labelPosition = new FrameLayout.LayoutParams(-1,-2, Gravity.BOTTOM|Gravity.LEFT);
        labelPosition.setMargins(18,0,18,8);
        layout.addView(status,labelPosition);
        Button pick = new Button(this);
        pick.setText("Import LCE archive");
        FrameLayout.LayoutParams buttonPosition = new FrameLayout.LayoutParams(-2,-2,Gravity.RIGHT|Gravity.BOTTOM);
        buttonPosition.setMargins(10,10,16,52);
        layout.addView(pick,buttonPosition);
        pick.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, PICK_ARCHIVE);
        });
        setContentView(layout);
        File existing = new File(getFilesDir(),"MediaWindows64.arc");
        if(existing.isFile()) new Thread(() -> inspect(existing)).start();
    }

    private void inspect(File f) {
        final String result = nativeInspectArchive(f.getAbsolutePath());
        runOnUiThread(() -> status.setText(result));
    }

    @Override public void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=PICK_ARCHIVE || result!=RESULT_OK || data==null || data.getData()==null) return;
        final Uri uri = data.getData();
        status.setText("Importing...");
        new Thread(() -> {
            File destination = new File(getFilesDir(),"MediaWindows64.arc");
            try(InputStream input=getContentResolver().openInputStream(uri);
                FileOutputStream output=new FileOutputStream(destination)) {
                if(input==null) throw new IllegalStateException("Document could not be opened");
                byte[] b = new byte[131072];
                int n;
                while((n=input.read(b))!=-1) output.write(b,0,n);
                inspect(destination);
            } catch(Exception e) {
                runOnUiThread(() -> status.setText("Import failed: "+e.getMessage()));
            }
        }).start();
    }

    @Override protected void onPause() { super.onPause(); if(surface!=null)surface.onPause(); }
    @Override protected void onResume() { super.onResume(); if(surface!=null)surface.onResume(); }
}
