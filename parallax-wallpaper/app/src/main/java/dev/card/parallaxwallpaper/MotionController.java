package dev.card.parallaxwallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.*;
import android.view.Surface;
import android.view.WindowManager;

/**
 * Produces normalized -1..+1 wallpaper parallax from the gravity direction.
 * Gravity is remapped into the CURRENT DISPLAY orientation so phones/tablets
 * behave the same in portrait, landscape-left and landscape-right.
 */
public final class MotionController implements SensorEventListener {
    private final SensorManager sm;
    private final Sensor sensor;
    private final Context context;
    private final boolean accelerometerFallback;
    private final WindowManager windowManager;

    private boolean baselineSet = false;
    private boolean filterSet = false;
    private final float[] filtered = new float[3];
    private float baseScreenX, baseZ;
    private volatile float x, y;
    private long eventCount = 0;
    private float maxExcursion = 0f;
    private boolean registered = false;
    private int lastRotation = -1;

    public MotionController(Context c) {
        context = c.getApplicationContext();
        sm = (SensorManager)c.getSystemService(Context.SENSOR_SERVICE);
        windowManager = (WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        boolean accel = false;
        if (s == null) {
            s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            accel = true;
        }
        sensor = s;
        accelerometerFallback = accel;
    }

    public void start() {
        baselineSet = false;
        filterSet = false;
        x = y = 0f;
        eventCount = 0;
        maxExcursion = 0f;
        lastRotation = displayRotation();
        registered = sensor != null && sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        persistDiagnostics();
    }

    public void stop() {
        sm.unregisterListener(this);
        persistDiagnostics();
    }

    public float x() { return x; }
    public float y() { return y; }
    public String sensorName() { return sensor == null ? "none" : sensor.getName(); }
    public boolean registered() { return registered; }

    @Override public void onSensorChanged(SensorEvent e) {
        eventCount++;

        float gx=e.values[0], gy=e.values[1], gz=e.values[2];
        if(accelerometerFallback){
            final float a=0.86f;
            if(!filterSet){
                filtered[0]=gx; filtered[1]=gy; filtered[2]=gz; filterSet=true;
            } else {
                filtered[0]=a*filtered[0]+(1f-a)*gx;
                filtered[1]=a*filtered[1]+(1f-a)*gy;
                filtered[2]=a*filtered[2]+(1f-a)*gz;
            }
            gx=filtered[0]; gy=filtered[1]; gz=filtered[2];
        }

        float g=(float)Math.sqrt(gx*gx+gy*gy+gz*gz);
        if(g<1e-3f) return;
        float nx=gx/g, ny=gy/g, nz=gz/g;

        int rotation=displayRotation();
        if(rotation!=lastRotation){
            lastRotation=rotation;
            baselineSet=false;
            x=y=0f;
        }

        // Sensor coordinates are tied to the device's natural orientation. Remap the
        // horizontal component into screen space so landscape tablets/phones don't swap axes.
        float screenX;
        switch(rotation){
            case Surface.ROTATION_90:  screenX=ny;  break;
            case Surface.ROTATION_180: screenX=-nx; break;
            case Surface.ROTATION_270: screenX=-ny; break;
            case Surface.ROTATION_0:
            default:                   screenX=nx;  break;
        }

        if(!baselineSet){
            baseScreenX=screenX;
            baseZ=nz;
            baselineSet=true;
            return;
        }

        // Roughly 45 degrees of physical tilt from the current orientation reaches full travel.
        final float full=(float)Math.sin(Math.toRadians(45.0));

        // Horizontal sign is intentionally inverted so the virtual viewpoint follows the
        // side the screen is tilted toward. Z handles forward/back tilt in every rotation.
        float targetX=clamp(-(screenX-baseScreenX)/full,-1f,1f);
        float targetY=clamp(-(nz-baseZ)/full,-1f,1f);

        x += (targetX-x)*0.28f;
        y += (targetY-y)*0.28f;
        maxExcursion=Math.max(maxExcursion,Math.max(Math.abs(x),Math.abs(y)));

        if((eventCount%60)==0) persistDiagnostics();
    }

    private int displayRotation(){
        try {
            return windowManager != null ? windowManager.getDefaultDisplay().getRotation() : Surface.ROTATION_0;
        } catch(Throwable ignored) {
            return Surface.ROTATION_0;
        }
    }

    private void persistDiagnostics(){
        SharedPreferences.Editor ed=context.getSharedPreferences(PackStore.PREFS,0).edit();
        ed.putString("motion_sensor",sensorName());
        ed.putBoolean("motion_registered",registered);
        ed.putLong("motion_events",eventCount);
        ed.putFloat("motion_x",x);
        ed.putFloat("motion_y",y);
        ed.putFloat("motion_max",maxExcursion);
        ed.putInt("motion_rotation",lastRotation);
        ed.apply();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
}
