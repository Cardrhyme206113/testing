# Phone Motion Server

Minimal Android LAN API that keeps motion detection running with the screen off.

## Behavior

- Foreground service + partial wake lock
- ~50 Hz accelerometer/linear-acceleration + gyroscope sampling
- Two-tier motion detector: fast obvious movement and slower/gentler handling
- HTTP server bound to `0.0.0.0:8765`
- Phone UI shows the active LAN IPv4 address
- Starts again after normal reboot when monitoring is enabled
- API response contains battery, charging state, movement state and liveness

## API

Poll every ~200 ms:

```text
GET http://PHONE_IP:8765/api/state
```

Example response:

```json
{
  "api_version": 1,
  "alive": true,
  "server_time_ms": 1787470000000,
  "service_uptime_ms": 123456,
  "boot_id": "...",
  "lan_ip": "192.168.1.42",
  "port": 8765,
  "recommended_poll_ms": 200,
  "dead_after_ms": 5000,
  "battery": {
    "percent": 73,
    "charging": false,
    "temperature_c": 31.8
  },
  "movement": {
    "id": 12,
    "active": false,
    "detected_at_ms": 1787470000123,
    "age_ms": 87,
    "strength": 0.244
  },
  "error": null
}
```

`movement.id` increments once per movement episode. Keep the previous ID client-side; if the new value differs, movement occurred even if it happened between HTTP polls.

A successful `/api/state` response is itself the keepalive. A client should poll about every 200 ms and only declare the phone unavailable after 5 seconds without a successful response.

## Xiaomi / HyperOS

The service uses Android's foreground-service model and a partial wake lock. For best screen-off reliability on HyperOS, use the in-app **Allow unrestricted battery** button. If HyperOS still kills the app, enable **Autostart** and set its battery setting to **No restrictions** in Xiaomi settings.

## Build

GitHub Actions builds an installable debug APK. The workflow artifact is named `motion-server-debug-apk`.
