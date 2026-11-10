package app.musemovelog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.IBinder;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground service that records location fixes and steps while enabled. */
public class TrackingService extends Service implements LocationListener, SensorEventListener {

    public static final String ACTION_STOP = "app.musemovelog.STOP";
    private static final String PREFS = "movelog";
    private static final String KEY_ENABLED = "tracking_enabled";
    private static final String CHANNEL = "tracking";
    private static final int NOTIF_ID = 1;

    private Store store;
    private Segmenter segmenter;
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private ExecutorService io;
    private long lastPruneMs;

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, TrackingService.class);
        ctx.startForegroundService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, TrackingService.class));
    }

    /** Steps recorded today (local day), 0 if none yet. */
    public static int stepsToday(Context ctx) {
        Store s = new Store(ctx);
        String day = s.getKv("steps_day", "");
        if (!day.equals(dayKey(System.currentTimeMillis()))) return 0;
        try {
            return Integer.parseInt(s.getKv("steps_today", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String dayKey(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(ts);
    }

    static long dayStart(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        io = Executors.newSingleThreadExecutor();
        store.closeDangling(System.currentTimeMillis());
        segmenter = new Segmenter(store, new Segmenter.Listener() {
            @Override
            public void onStayKept(long stayId, double lat, double lon) {
                geocodeStay(stayId, lat, lon);
            }

            @Override
            public void onSegmentChanged() {
                updateNotification();
            }
        });

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "移動の記録", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIF_ID, buildNotification());

        locationManager = getSystemService(LocationManager.class);
        try {
            if (locationManager.getProvider(LocationManager.GPS_PROVIDER) != null) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 20_000, 10, this);
            }
            if (locationManager.getProvider(LocationManager.NETWORK_PROVIDER) != null) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 30_000, 25, this);
            }
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) onLocationChanged(last);
        } catch (SecurityException e) {
            Dbg.log(this, "location permission missing: " + e);
        }

        sensorManager = getSystemService(SensorManager.class);
        Sensor steps = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (steps != null) {
            sensorManager.registerListener(this, steps, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            Dbg.log(this, "no step counter sensor");
        }
        Dbg.log(this, "tracking service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            setEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            locationManager.removeUpdates(this);
        } catch (Exception ignored) {
        }
        try {
            sensorManager.unregisterListener(this);
        } catch (Exception ignored) {
        }
        if (segmenter != null) segmenter.closeCurrent(System.currentTimeMillis());
        if (io != null) io.shutdownNow();
        Dbg.log(this, "tracking service stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- location ----

    @Override
    public void onLocationChanged(Location location) {
        store.addPoint(location.getTime(), location.getLatitude(), location.getLongitude(),
                location.hasSpeed() ? location.getSpeed() : 0f, location.getAccuracy());
        segmenter.onFix(location);
        long now = System.currentTimeMillis();
        if (now - lastPruneMs > 6 * 3600_000L) {
            lastPruneMs = now;
            store.prunePoints(now - 14L * 24 * 3600_000L);
        }
        updateNotification();
    }

    // ---- steps ----

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_STEP_COUNTER) return;
        float cum = event.values[0];
        long now = System.currentTimeMillis();
        String today = dayKey(now);
        String day = store.getKv("steps_day", "");
        double base = parseDouble(store.getKv("steps_base", "NaN"));
        double extra = parseDouble(store.getKv("steps_extra", "0"));
        double lastCum = parseDouble(store.getKv("steps_last", "NaN"));
        long prevToday = 0;
        try {
            prevToday = Long.parseLong(store.getKv("steps_today", "0"));
        } catch (NumberFormatException ignored) {
        }
        boolean rolled = !today.equals(day) || Double.isNaN(base);
        if (rolled) {
            // Day rolled over: persist the finished day's steps so the
            // calendar can show them later (steps_today itself is per-day).
            if (!day.isEmpty() && !Double.isNaN(base)) {
                try {
                    long prevSteps = Long.parseLong(store.getKv("steps_today", "0"));
                    if (prevSteps > 0) {
                        store.upsertDaySummaryMax(day, prevSteps, -1, -1);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            base = cum;
            extra = 0;
            day = today;
        } else if (!Double.isNaN(lastCum) && cum < lastCum) {
            // Sensor reset (reboot): bank what we had and re-base.
            extra += (lastCum - base);
            base = cum;
        }
        long todaySteps = Math.max(0, Math.round(extra + (cum - base)));
        store.setKv("steps_day", day);
        store.setKv("steps_base", String.valueOf(base));
        store.setKv("steps_extra", String.valueOf(extra));
        store.setKv("steps_last", String.valueOf(cum));
        store.setKv("steps_today", String.valueOf(todaySteps));
        // Feed the day chart: accumulate this event's step delta into the
        // current hour bucket (on a rollover the whole count is new-day).
        long delta = rolled ? todaySteps : todaySteps - prevToday;
        if (delta > 0) {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            store.addHourlySteps(today, hour, delta);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    // ---- geocoding stays ----

    private void geocodeStay(long stayId, double lat, double lon) {
        io.execute(() -> {
            // Private zones (home etc.): never send the coordinates anywhere.
            if (store.isPrivate(lat, lon)) return;
            String label = facilityName(lat, lon);
            if (label == null || label.isEmpty()) {
                try {
                    Geocoder g = new Geocoder(this, Locale.JAPAN);
                    List<Address> list = g.getFromLocation(lat, lon, 1);
                    if (list != null && !list.isEmpty()) {
                        Address a = list.get(0);
                        StringBuilder sb = new StringBuilder();
                        if (a.getLocality() != null) sb.append(a.getLocality());
                        if (a.getSubLocality() != null) sb.append(a.getSubLocality());
                        if (a.getThoroughfare() != null) sb.append(a.getThoroughfare());
                        if (sb.length() == 0 && a.getMaxAddressLineIndex() >= 0) {
                            sb.append(a.getAddressLine(0));
                        }
                        label = sb.toString();
                    }
                } catch (Exception e) {
                    Dbg.log(this, "geocode failed: " + e);
                }
            }
            if (label != null && !label.isEmpty()) {
                store.setStayLabel(stayId, label);
            }
        });
    }

    /**
     * Facility / POI name for a point from OpenStreetMap (Nominatim), e.g. a venue,
     * station or shop name when one is mapped there. Null when none is known.
     */
    private String facilityName(double lat, double lon) {
        java.net.HttpURLConnection conn = null;
        try {
            String url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&lat="
                    + lat + "&lon=" + lon;
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "MoveLog/1.14 (personal Android app)");
            if (conn.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = conn.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            org.json.JSONObject json = new org.json.JSONObject(
                    bos.toString("UTF-8"));
            String name = json.optString("name", "");
            return name.isEmpty() ? null : name;
        } catch (Exception e) {
            Dbg.log(this, "facility lookup failed: " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---- notification ----

    private Notification buildNotification() {
        long now = System.currentTimeMillis();
        long from = dayStart(now);
        double km = store.distanceIn(from, now) / 1000.0;
        String text = String.format(Locale.JAPAN, "記録中 · 今日 %.1f km · %,d 歩",
                km, stepsToday(this));
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, TrackingService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Move Log")
                .setContentText(text)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED || true) {
            try {
                getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
            } catch (Exception ignored) {
            }
        }
    }
}
