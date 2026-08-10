package dev.card.parallaxwallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.*;

/**
 * Produces normalized -1..+1 wallpaper parallax from the gravity direction in
 * device coordinates. For a live wallpaper we only need tilt, not absolute 3D
 * heading, so TYPE_GRAVITY is simpler and more reliable than rotation vectors.
 * Falls back to a low-pass filtered accelerometer on devices without GRAVITY.
 */
public final class MotionController implements SensorEventListener {
    private final SensorManager sm;
    private final Sensor sensor;
    private final Context context;
    private final boolean accelerometerFallback;

    private boolean baselineSet = false;
    private boolean filterSet = false;
    private final float[] filtered = new float[3];
    private float baseX, baseZ;
    private volatile float x, y;
    private long eventCount = 0;
    private float maxExcursion = 0f;
    private boolean registered = false;

    public MotionController(Context c) {
        context = c.getApplicationContext();
        sm = (SensorManager)c.getSystemService(Context.SENSOR_SERVICE);
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
            // Remove hand motion / taps and keep the slowly varying gravity component.
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
        float nx=gx/g, nz=gz/g;

        if(!baselineSet){
            baseX=nx; baseZ=nz; baselineSet=true;
            return;
        }

        // Roughly 45 degrees of physical tilt from the starting pose reaches full travel.
        final float full=(float)Math.sin(Math.toRadians(45.0));

        // Horizontal direction intentionally opposes the raw gravity-axis change so the
        // virtual viewpoint follows the side the phone is tilted toward.
        float targetX=clamp(-(nx-baseX)/full,-1f,1f);
        float targetY=clamp(-(nz-baseZ)/full,-1f,1f);

        // Fast enough to feel attached to the phone, still filtered against tiny hand shake.
        x += (targetX-x)*0.28f;
        y += (targetY-y)*0.28f;
        maxExcursion=Math.max(maxExcursion,Math.max(Math.abs(x),Math.abs(y)));

        // Roughly once per second at GAME rate. Lets the activity prove that sensor data moved.
        if((eventCount%60)==0) persistDiagnostics();
    }

    private void persistDiagnostics(){
        SharedPreferences.Editor ed=context.getSharedPreferences(PackStore.PREFS,0).edit();
        ed.putString("motion_sensor",sensorName());
        ed.putBoolean("motion_registered",registered);
        ed.putLong("motion_events",eventCount);
        ed.putFloat("motion_x",x);
        ed.putFloat("motion_y",y);
        ed.putFloat("motion_max",maxExcursion);
        ed.apply();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
}
