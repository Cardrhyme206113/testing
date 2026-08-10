package dev.card.parallaxwallpaper;

import android.content.Context;
import android.hardware.*;

/**
 * Produces normalized -1..+1 parallax motion from the phone's orientation
 * relative to the orientation at which the wallpaper became visible.
 *
 * We deliberately do NOT use SensorManager.getOrientation() pitch/roll here.
 * Those Euler components depend heavily on how the device is oriented relative
 * to the world and can map an upright portrait left/right tilt into azimuth.
 * Relative quaternions keep the motion in the phone's starting coordinate frame.
 */
public final class MotionController implements SensorEventListener {
    private final SensorManager sm;
    private final Sensor sensor;
    private final float[] baseQ = new float[4]; // [w,x,y,z]
    private boolean baselineSet = false;
    private volatile float x, y;

    public MotionController(Context c) {
        sm = (SensorManager)c.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        sensor = s != null ? s : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    public void start() {
        baselineSet = false;
        x = y = 0f;
        if (sensor != null) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
    }
    public void stop() { sm.unregisterListener(this); }
    public float x() { return x; }
    public float y() { return y; }
    public String sensorName() { return sensor == null ? "none" : sensor.getName(); }

    @Override public void onSensorChanged(SensorEvent e) {
        float[] q = new float[4];
        SensorManager.getQuaternionFromVector(q, e.values); // Android returns [w,x,y,z]

        if (!baselineSet) {
            System.arraycopy(q, 0, baseQ, 0, 4);
            baselineSet = true;
            return;
        }

        // Relative orientation qRel = inverse(base) * current.
        float bw=baseQ[0], bx=-baseQ[1], by=-baseQ[2], bz=-baseQ[3];
        float cw=q[0], cx=q[1], cy=q[2], cz=q[3];
        float rw = bw*cw - bx*cx - by*cy - bz*cz;
        float rx = bw*cx + bx*cw + by*cz - bz*cy;
        float ry = bw*cy - bx*cz + by*cw + bz*cx;
        float rz = bw*cz + bx*cy - by*cx + bz*cw;

        float n=(float)Math.sqrt(rw*rw+rx*rx+ry*ry+rz*rz);
        if(n>1e-6f){rw/=n;rx/=n;ry/=n;rz/=n;}
        // q and -q are the same orientation. Pick the short-arc representation.
        if(rw<0f){rw=-rw;rx=-rx;ry=-ry;rz=-rz;}
        rw=clamp(rw,-1f,1f);

        float angle=2f*(float)Math.acos(rw);
        float s=(float)Math.sqrt(Math.max(0f,1f-rw*rw));
        float rotX,rotY;
        if(s<1e-4f){
            // Small-angle quaternion: rotation vector ~= 2*q.xyz.
            rotX=2f*rx;
            rotY=2f*ry;
        } else {
            rotX=rx/s*angle;
            rotY=ry/s*angle;
        }

        // About 10 degrees of physical tilt reaches full parallax travel.
        float range=(float)Math.toRadians(10.0);
        float targetX=clamp(rotY/range,-1f,1f);   // turn/tilt left-right
        float targetY=clamp(-rotX/range,-1f,1f); // tilt top/bottom

        // Responsive but not shaky.
        x += (targetX-x)*0.24f;
        y += (targetY-y)*0.24f;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
}
