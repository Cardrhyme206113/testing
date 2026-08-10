package dev.card.parallaxwallpaper;

import android.content.Context;
import android.hardware.*;

public final class MotionController implements SensorEventListener {
    private final SensorManager sm;
    private final Sensor sensor;
    private boolean baselineSet = false;
    private float basePitch, baseRoll;
    private volatile float x, y;

    public MotionController(Context c) {
        sm = (SensorManager)c.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        sensor = s != null ? s : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    public void start() { baselineSet = false; if (sensor != null) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME); }
    public void stop() { sm.unregisterListener(this); }
    public float x() { return x; }
    public float y() { return y; }

    @Override public void onSensorChanged(SensorEvent e) {
        float[] r = new float[9]; float[] o = new float[3];
        SensorManager.getRotationMatrixFromVector(r, e.values);
        SensorManager.getOrientation(r, o);
        float pitch = o[1], roll = o[2];
        if (!baselineSet) { basePitch = pitch; baseRoll = roll; baselineSet = true; }
        float dp = wrap(pitch - basePitch), dr = wrap(roll - baseRoll);
        float targetX = clamp(dr / (float)Math.toRadians(12), -1f, 1f);
        float targetY = clamp(-dp / (float)Math.toRadians(12), -1f, 1f);
        x += (targetX - x) * 0.16f;
        y += (targetY - y) * 0.16f;
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
    private static float wrap(float a){while(a>(float)Math.PI)a-=2f*(float)Math.PI;while(a<-(float)Math.PI)a+=2f*(float)Math.PI;return a;}
}
