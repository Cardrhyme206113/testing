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
    private SeekBar sourceFovBar, screenFovBar, zoomBar;
    private TextView sourceFovLabel, screenFovLabel, zoomLabel;
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

        TextView viewHelp = text("Camera setup: Source FOV = how the parallax was captured. Screen FOV = the virtual camera you want. Zoom is an extra optical crop; more zoom naturally makes parallax look faster.", 13, Color.rgb(155,158,170));
        viewHelp.setPadding(0,28,0,12); root.addView(viewHelp);

        sourceFovLabel = text("Source / parallax FOV", 15, Color.WHITE); root.addView(sourceFovLabel);
        sourceFovBar = new SeekBar(this);
        sourceFovBar.setMax(SceneRenderer.MAX_FOV - SceneRenderer.MIN_FOV);
        root.addView(sourceFovBar, new LinearLayout.LayoutParams(-1,-2));
        Button resetSource = button("Use detected scene FOV"); root.addView(resetSource);

        screenFovLabel = text("Wanted screen FOV", 15, Color.WHITE);
        screenFovLabel.setPadding(0,18,0,0); root.addView(screenFovLabel);
        screenFovBar = new SeekBar(this);
        screenFovBar.setMax(SceneRenderer.MAX_FOV - SceneRenderer.MIN_FOV);
        root.addView(screenFovBar, new LinearLayout.LayoutParams(-1,-2));
        Button matchSource = button("Match screen FOV to source"); root.addView(matchSource);

        zoomLabel = text("Extra zoom", 15, Color.WHITE);
        zoomLabel.setPadding(0,18,0,0); root.addView(zoomLabel);
        zoomBar = new SeekBar(this);
        zoomBar.setMax(Math.round((SceneRenderer.MAX_ZOOM-SceneRenderer.MIN_ZOOM)*100f));
        root.addView(zoomBar, new LinearLayout.LayoutParams(-1,-2));
        Button resetZoom = button("Reset zoom to 1.00×"); root.addView(resetZoom);

        TextView rangeHelp = text("FOV range 35°–120°  •  Zoom 1.00×–1.60×  •  Landscape keeps a small automatic safety crop", 12, Color.rgb(125,128,140));
        rangeHelp.setPadding(0,4,0,0); root.addView(rangeHelp);

        status = text(currentStatus(), 14, Color.rgb(190,190,200));
        status.setPadding(0,26,0,0); root.addView(status);

        TextView note = text("Format: ParallaxPack v1 • static fused triangle mesh • gravity/accelerometer tilt • OpenGL ES 3", 12, Color.rgb(125,128,140));
        note.setPadding(0,30,0,0); root.addView(note);
        setContentView(scroll);

        choose.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, 42);
        });
        setButton.setOnClickListener(v -> launchWallpaperPicker());

        sourceFovBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void changed(int progress, boolean fromUser) {
                if(fromUser) {
                    float value=SceneRenderer.MIN_FOV+progress;
                    android.content.SharedPreferences p=getSharedPreferences(PackStore.PREFS,0);
                    p.edit().putFloat(SceneRenderer.SOURCE_FOV_KEY,value).apply();
                    if(!p.contains(SceneRenderer.SCREEN_FOV_KEY)) screenFovBar.setProgress(progress);
                }
                updateViewLabels();
            }
        });

        screenFovBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void changed(int progress, boolean fromUser) {
                if(fromUser) getSharedPreferences(PackStore.PREFS,0).edit()
                        .putFloat(SceneRenderer.SCREEN_FOV_KEY,SceneRenderer.MIN_FOV+progress).apply();
                updateViewLabels();
            }
        });

        zoomBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void changed(int progress, boolean fromUser) {
                if(fromUser) getSharedPreferences(PackStore.PREFS,0).edit()
                        .putFloat(SceneRenderer.ZOOM_KEY,SceneRenderer.MIN_ZOOM+progress/100f).apply();
                updateViewLabels();
            }
        });

        resetSource.setOnClickListener(v -> {
            android.content.SharedPreferences p=getSharedPreferences(PackStore.PREFS,0);
            p.edit().remove(SceneRenderer.SOURCE_FOV_KEY).apply();
            syncViewControls();
        });
        matchSource.setOnClickListener(v -> {
            getSharedPreferences(PackStore.PREFS,0).edit().remove(SceneRenderer.SCREEN_FOV_KEY).apply();
            syncViewControls();
        });
        resetZoom.setOnClickListener(v -> {
            getSharedPreferences(PackStore.PREFS,0).edit().remove(SceneRenderer.ZOOM_KEY).apply();
            syncViewControls();
        });

        syncViewControls();
    }

    @Override protected void onResume() {
        super.onResume();
        if(status!=null) status.setText(currentStatus());
        if(setButton!=null) setButton.setEnabled(PackStore.currentScene(this)!=null);
        syncViewControls();
    }

    private float detectedSceneFov() {
        File f=PackStore.currentScene(this);
        if(f==null) return 70f;
        try {
            JSONObject p=PackStore.readPack(f);
            return clamp((float)p.optDouble("verticalFovDegrees",70.0), SceneRenderer.MIN_FOV, SceneRenderer.MAX_FOV);
        } catch(Throwable ignored) { return 70f; }
    }

    private void syncViewControls() {
        if(sourceFovBar==null) return;
        android.content.SharedPreferences p=getSharedPreferences(PackStore.PREFS,0);
        float detected=detectedSceneFov();
        float source=clamp(p.getFloat(SceneRenderer.SOURCE_FOV_KEY,detected),SceneRenderer.MIN_FOV,SceneRenderer.MAX_FOV);
        float screen=clamp(p.getFloat(SceneRenderer.SCREEN_FOV_KEY,source),SceneRenderer.MIN_FOV,SceneRenderer.MAX_FOV);
        float zoom=clamp(p.getFloat(SceneRenderer.ZOOM_KEY,1f),SceneRenderer.MIN_ZOOM,SceneRenderer.MAX_ZOOM);
        sourceFovBar.setProgress(Math.round(source)-SceneRenderer.MIN_FOV);
        screenFovBar.setProgress(Math.round(screen)-SceneRenderer.MIN_FOV);
        zoomBar.setProgress(Math.round((zoom-SceneRenderer.MIN_ZOOM)*100f));
        updateViewLabels();
    }

    private void updateViewLabels() {
        if(sourceFovBar==null) return;
        android.content.SharedPreferences p=getSharedPreferences(PackStore.PREFS,0);
        int source=SceneRenderer.MIN_FOV+sourceFovBar.getProgress();
        int screen=SceneRenderer.MIN_FOV+screenFovBar.getProgress();
        float zoom=SceneRenderer.MIN_ZOOM+zoomBar.getProgress()/100f;
        float effective=effectiveFov(screen,zoom);
        sourceFovLabel.setText("Source / parallax FOV: " + source + "°" + (p.contains(SceneRenderer.SOURCE_FOV_KEY)?"  (custom)":"  (detected)"));
        screenFovLabel.setText("Wanted screen FOV: " + screen + "°" + (p.contains(SceneRenderer.SCREEN_FOV_KEY)?"  (custom)":"  (matches source)"));
        zoomLabel.setText(String.format("Extra zoom: %.2f×  •  effective ≈ %.1f°",zoom,effective));
    }

    private static float effectiveFov(float screenFov,float zoom){
        double half=Math.toRadians(screenFov)*0.5;
        return (float)Math.toDegrees(2.0*Math.atan(Math.tan(half)/Math.max(0.01,zoom)));
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
                        .remove(SceneRenderer.SOURCE_FOV_KEY)
                        .remove(SceneRenderer.SCREEN_FOV_KEY)
                        .remove(SceneRenderer.ZOOM_KEY)
                        .apply();
                runOnUiThread(() -> {
                    status.setText("Imported ✓  " + String.format("%,d", r.triangles) + " triangles\n" + r.dir.getName());
                    setButton.setEnabled(true);
                    syncViewControls();
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

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        public abstract void changed(int progress,boolean fromUser);
        @Override public final void onProgressChanged(SeekBar s,int p,boolean f){changed(p,f);}
        @Override public void onStartTrackingTouch(SeekBar s){}
        @Override public void onStopTrackingTouch(SeekBar s){}
    }

    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
    private TextView text(String s, int sp, int color) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); return v; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0,10,0,10); b.setLayoutParams(p); return b; }
}
