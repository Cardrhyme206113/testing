package dev.card.parallaxwallpaper;

import android.app.*;
import android.app.WallpaperManager;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

import java.io.File;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private TextView status;
    private Button setButton;
    private final java.util.concurrent.ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER_HORIZONTAL); root.setPadding(48,80,48,48); root.setBackgroundColor(Color.rgb(14,15,18));
        TextView title = text("Parallax Wallpaper", 28, Color.WHITE); root.addView(title);
        TextView sub = text("Pick one of your fused scene ZIPs. It is normalized automatically into ParallaxPack v1 and used as a 3D live wallpaper.", 15, Color.rgb(185,188,200));
        sub.setPadding(0,18,0,36); root.addView(sub);
        Button choose = button("Choose scene ZIP"); root.addView(choose);
        setButton = button("Set current scene as wallpaper"); setButton.setEnabled(PackStore.currentScene(this) != null); root.addView(setButton);
        status = text(currentStatus(), 14, Color.rgb(190,190,200)); status.setPadding(0,30,0,0); root.addView(status);
        TextView note = text("Format: ParallaxPack v1 • static fused triangle mesh • 9 beauty textures • relative gyro/quaternion camera motion • OpenGL ES 3", 12, Color.rgb(125,128,140));
        note.setPadding(0,30,0,0); root.addView(note);
        setContentView(root);

        choose.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, 42);
        });
        setButton.setOnClickListener(v -> launchWallpaperPicker());
    }

    @Override protected void onResume() {
        super.onResume();
        if(status!=null) status.setText(currentStatus());
        if(setButton!=null) setButton.setEnabled(PackStore.currentScene(this)!=null);
    }

    private String currentStatus() {
        File f = PackStore.currentScene(this);
        if(f==null) return "No scene imported yet.";
        String render = getSharedPreferences(PackStore.PREFS,0).getString("renderer_status", "Renderer has not started yet.");
        return "Current scene: " + f.getName() + "\nRenderer: " + render;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 42 || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData(); if (uri == null) return;
        status.setText("Importing scene…");
        io.execute(() -> {
            try {
                PackStore.ImportResult r = PackStore.importScene(this, uri);
                getSharedPreferences(PackStore.PREFS,0).edit().remove("renderer_status").apply();
                runOnUiThread(() -> {
                    status.setText("Imported ✓  " + String.format("%,d", r.triangles) + " triangles\n" + r.dir.getName());
                    setButton.setEnabled(true);
                    launchWallpaperPicker();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> status.setText("Import failed: " + t.getMessage()));
            }
        });
    }

    private void launchWallpaperPicker() {
        try {
            Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, ParallaxWallpaperService.class));
            startActivity(i);
        } catch (Throwable t) {
            startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
        }
    }

    private TextView text(String s, int sp, int color) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); return v; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0,10,0,10); b.setLayoutParams(p); return b; }
}
