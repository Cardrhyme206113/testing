package dev.card.parallaxwallpaper;

import android.app.*;
import android.app.WallpaperManager;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private TextView status;
    private Button setButton;
    private SeekBar fovBar;
    private TextView fovLabel;
    private Button resetFovButton;
    private final java.util.concurrent.ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48,80,48,48);
        root.setBackgroundColor(Color.rgb(14,15,18));
        scroll.addView(root, new ScrollView.LayoutParams(-1,-2));

        TextView title = text("Parallax Wallpaper", 28, Color.WHITE); root.addView(title);
        TextView sub = text("Pick one of your fused scene ZIPs. It is normalized automatically into ParallaxPack v1 and used as a 3D live wallpaper.", 15, Color.rgb(185,188,200));
        sub.setPadding(0,18,0,30); root.addView(sub);

        Button choose = button("Choose scene ZIP"); root.addView(choose);
        setButton = button("Set current scene as wallpaper");
        setButton.setEnabled(PackStore.currentScene(this) != null);
        root.addView(setButton);

        fovLabel = text("View FOV", 15, Color.WHITE);
        fovLabel.setPadding(0,30,0,4);
        root.addView(fovLabel);

        TextView fovHelp = text("35° = strong zoom  •  70° = normal  •  110° = ultrawide", 12, Color.rgb(145,148,160));
        fovHelp.setPadding(0,0,0,4);
        root.addView(fovHelp);

        fovBar = new SeekBar(this);
        fovBar.setMax(SceneRenderer.MAX_FOV - SceneRenderer.MIN_FOV);
        root.addView(fovBar, new LinearLayout.LayoutParams(-1,-2));
        resetFovButton = button("Use scene FOV");
        root.addView(resetFovButton);

        status = text(currentStatus(), 14, Color.rgb(190,190,200));
        status.setPadding(0,26,0,0);
        root.addView(status);

        TextView note = text("Format: ParallaxPack v1 • static fused triangle mesh • 9 beauty textures • gravity/accelerometer tilt • OpenGL ES 3", 12, Color.rgb(125,128,140));
        note.setPadding(0,30,0,0); root.addView(note);
        setContentView(scroll);

        choose.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, 42);
        });
        setButton.setOnClickListener(v -> launchWallpaperPicker());

        fovBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int fov = SceneRenderer.MIN_FOV + progress;
                if(fromUser) {
                    getSharedPreferences(PackStore.PREFS,0).edit().putFloat(SceneRenderer.FOV_OVERRIDE_KEY, fov).apply();
                }
                updateFovLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        resetFovButton.setOnClickListener(v -> {
            getSharedPreferences(PackStore.PREFS,0).edit().remove(SceneRenderer.FOV_OVERRIDE_KEY).apply();
            syncFovControls();
        });

        syncFovControls();
    }

    @Override protected void onResume() {
        super.onResume();
        if(status!=null) status.setText(currentStatus());
        if(setButton!=null) setButton.setEnabled(PackStore.currentScene(this)!=null);
        syncFovControls();
    }

    private float sceneFov() {
        File f=PackStore.currentScene(this);
        if(f==null) return 70f;
        try {
            JSONObject p=PackStore.readPack(f);
            return clamp((float)p.optDouble("verticalFovDegrees",70.0), SceneRenderer.MIN_FOV, SceneRenderer.MAX_FOV);
        } catch(Throwable ignored) {
            return 70f;
        }
    }

    private void syncFovControls() {
        if(fovBar==null || fovLabel==null) return;
        android.content.SharedPreferences p=getSharedPreferences(PackStore.PREFS,0);
        float scene=sceneFov();
        boolean overridden=p.contains(SceneRenderer.FOV_OVERRIDE_KEY);
        float active=overridden ? p.getFloat(SceneRenderer.FOV_OVERRIDE_KEY,scene) : scene;
        active=clamp(active,SceneRenderer.MIN_FOV,SceneRenderer.MAX_FOV);
        fovBar.setProgress(Math.round(active)-SceneRenderer.MIN_FOV);
        updateFovLabel();
    }

    private void updateFovLabel() {
        if(fovBar==null || fovLabel==null) return;
        int active=SceneRenderer.MIN_FOV+fovBar.getProgress();
        boolean overridden=getSharedPreferences(PackStore.PREFS,0).contains(SceneRenderer.FOV_OVERRIDE_KEY);
        if(overridden) fovLabel.setText("View FOV: " + active + "°  (custom)");
        else fovLabel.setText("View FOV: " + active + "°  (scene default)");
    }

    private String currentStatus() {
        File f = PackStore.currentScene(this);
        if(f==null) return "No scene imported yet.";
        android.content.SharedPreferences p=getSharedPreferences(PackStore.PREFS,0);
        String render=p.getString("renderer_status", "Renderer has not started yet.");
        String sensor=p.getString("motion_sensor", "unknown");
        boolean registered=p.getBoolean("motion_registered",false);
        long events=p.getLong("motion_events",0);
        float mx=p.getFloat("motion_x",0f), my=p.getFloat("motion_y",0f), max=p.getFloat("motion_max",0f);
        return "Current scene: " + f.getName() +
                "\nRenderer: " + render +
                "\nTilt sensor: " + sensor + " • registered=" + registered +
                "\nEvents=" + events + " • last=(" + String.format("%.2f",mx) + ", " + String.format("%.2f",my) + ") • max=" + String.format("%.2f",max);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 42 || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData(); if (uri == null) return;
        status.setText("Importing scene…");
        io.execute(() -> {
            try {
                PackStore.ImportResult r = PackStore.importScene(this, uri);
                getSharedPreferences(PackStore.PREFS,0).edit()
                        .remove("renderer_status")
                        .remove(SceneRenderer.FOV_OVERRIDE_KEY)
                        .apply();
                runOnUiThread(() -> {
                    status.setText("Imported ✓  " + String.format("%,d", r.triangles) + " triangles\n" + r.dir.getName());
                    setButton.setEnabled(true);
                    syncFovControls();
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

    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
    private TextView text(String s, int sp, int color) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); return v; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0,10,0,10); b.setLayoutParams(p); return b; }
}
