package dev.card.parallaxwallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.*;
import android.view.Surface;
import android.view.WindowManager;

/**
 * Produces normalized -1..+1 wallpaper parallax from relative device rotation.
 *
 * Prefer the fused game-rotation vector: it gives precise two-axis relative motion
 * without magnetometer wobble. Gravity/accelerometer remains as a fallback.
 * Coordinates are remapped into the CURRENT DISPLAY orientation so portrait and
 * landscape use the same screen X/Y axes.
 */
public final class MotionController implements SensorEventListener {
    private static final float FULL_TILT_RAD = (float)Math.toRadians(30.0);
    private static final float HOLD_EPSILON = 0.0040f; // ~0.12 degrees at full scale

    private final SensorManager sm;
    private final Sensor sensor;
    private final Context context;
    private final boolean rotationVector;
    private final boolean accelerometerFallback;
    private final WindowManager windowManager;

    private boolean baselineSet = false;
    private boolean filterSet = false;
    private final float[] filtered = new float[3];
    private final float[] baseQuaternion = new float[4]; // w,x,y,z
    private final float[] currentQuaternion = new float[4]; // reused: no per-event GC
    private float baseScreenX, baseScreenY, baseScreenZ;
    private volatile float x, y;
    private long eventCount = 0;
    private float maxExcursion = 0f;
    private boolean registered = false;
    private int lastRotation = -1;

    public MotionController(Context c) {
        context = c.getApplicationContext();
        sm = (SensorManager)c.getSystemService(Context.SENSOR_SERVICE);
        windowManager = (WindowManager)c.getSystemService(Context.WINDOW_SERVICE);

        // Best fit for parallax: gyro-fused relative orientation, no magnetic-field wobble.
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        boolean rv = s != null;
        boolean accel = false;
        if (s == null) {
            s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            rv = s != null;
        }
        if (s == null) s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (s == null) {
            s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            accel = true;
        }
        sensor = s;
        rotationVector = rv;
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
        registered = false;
        persistDiagnostics();
    }

    public float x() { return x; }
    public float y() { return y; }
    public String sensorName() { return sensor == null ? "none" : sensor.getName(); }
    public boolean registered() { return registered; }

    @Override public void onSensorChanged(SensorEvent e) {
        eventCount++;

        int rotation = displayRotation();
        if(rotation != lastRotation){
            lastRotation = rotation;
            baselineSet = false;
            filterSet = false;
            x = y = 0f;
        }

        float targetX;
        float targetY;

        if(rotationVector){
            SensorManager.getQuaternionFromVector(currentQuaternion, e.values); // [w,x,y,z]
            float[] q=currentQuaternion;

            if(!baselineSet){
                System.arraycopy(q,0,baseQuaternion,0,4);
                baselineSet = true;
                return;
            }

            // Relative quaternion = inverse(base) * current. Its axis is expressed in
            // baseline device coordinates, so it remains clean even when the phone/tablet
            // started at an arbitrary angle.
            float bw=baseQuaternion[0], bx=baseQuaternion[1], by=baseQuaternion[2], bz=baseQuaternion[3];
            float cw=q[0], cx=q[1], cy=q[2], cz=q[3];
            float rw = bw*cw + bx*cx + by*cy + bz*cz;
            float rx = bw*cx - bx*cw - by*cz + bz*cy;
            float ry = bw*cy + bx*cz - by*cw - bz*cx;
            float rz = bw*cz - bx*cy + by*cx - bz*cw;

            // q and -q are the same orientation. Always choose the shortest relative arc.
            if(rw < 0f){ rw=-rw; rx=-rx; ry=-ry; rz=-rz; }
            float v=(float)Math.sqrt(rx*rx+ry*ry+rz*rz);
            float rotX=0f, rotY=0f;
            if(v > 1e-7f){
                float angle=2f*(float)Math.atan2(v,clamp(rw,-1f,1f));
                float k=angle/v;
                rotX=rx*k;
                rotY=ry*k;
            }

            // Remap natural device axes to current SCREEN axes.
            float screenRotX, screenRotY;
            switch(rotation){
                case Surface.ROTATION_90:
                    screenRotX=rotY;  screenRotY=-rotX; break;
                case Surface.ROTATION_180:
                    screenRotX=-rotX; screenRotY=-rotY; break;
                case Surface.ROTATION_270:
                    screenRotX=-rotY; screenRotY=rotX; break;
                case Surface.ROTATION_0:
                default:
                    screenRotX=rotX;  screenRotY=rotY; break;
            }

            // Horizontal parallax comes from rotation around screen Y; vertical parallax
            // comes from rotation around screen X. Both signs are intentionally reversed
            // versus the previous build, as requested.
            targetX=clamp(-screenRotY/FULL_TILT_RAD,-1f,1f);
            targetY=clamp(-screenRotX/FULL_TILT_RAD,-1f,1f);
        } else {
            // Fallback for hardware without a rotation-vector sensor.
            float gx=e.values[0], gy=e.values[1], gz=e.values[2];
            if(accelerometerFallback){
                // Lighter LPF than before: enough to strip linear-acceleration chatter,
                // without the old floaty/laggy feel.
                final float a=0.65f;
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

            float sx, sy;
            switch(rotation){
                case Surface.ROTATION_90:  sx=ny;  sy=-nx; break;
                case Surface.ROTATION_180: sx=-nx; sy=-ny; break;
                case Surface.ROTATION_270: sx=-ny; sy=nx;  break;
                case Surface.ROTATION_0:
                default:                   sx=nx;   sy=ny;  break;
            }

            if(!baselineSet){
                baseScreenX=sx;
                baseScreenY=sy;
                baseScreenZ=nz;
                baselineSet=true;
                return;
            }

            // Use angular deltas instead of raw Z delta. This fixes the old Y-axis issue
            // where vertical travel shrank dramatically depending on the starting angle.
            float horizontalAngle=(float)Math.asin(clamp(sx,-1f,1f))-
                    (float)Math.asin(clamp(baseScreenX,-1f,1f));
            float verticalAngle=wrapPi((float)Math.atan2(nz,sy)-
                    (float)Math.atan2(baseScreenZ,baseScreenY));

            // Reversed on both axes versus the previous build.
            targetX=clamp(horizontalAngle/FULL_TILT_RAD,-1f,1f);
            targetY=clamp(-verticalAngle/FULL_TILT_RAD,-1f,1f);
        }

        // Fast, nearly direct tracking. Tiny changes are held to suppress sensor shimmer;
        // real movement catches up aggressively instead of the old 0.30 EMA lag.
        x=track(x,targetX);
        y=track(y,targetY);
        maxExcursion=Math.max(maxExcursion,Math.max(Math.abs(x),Math.abs(y)));

        if((eventCount%60)==0) persistDiagnostics();
    }

    private static float track(float current,float target){
        float d=target-current;
        float ad=Math.abs(d);
        if(ad < HOLD_EPSILON) return current;
        float response = ad > 0.05f ? 0.90f : 0.72f;
        return current + d*response;
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
    private static float wrapPi(float v){
        while(v > Math.PI) v -= (float)(Math.PI*2.0);
        while(v < -Math.PI) v += (float)(Math.PI*2.0);
        return v;
    }
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
}
