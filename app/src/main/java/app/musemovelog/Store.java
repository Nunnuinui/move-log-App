package app.musemovelog;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Local storage: raw points (short-lived), stays, trips, and small key/value state. */
public class Store extends SQLiteOpenHelper {

    public static class Stay {
        public long id, startTs, endTs;
        public double lat, lon;
        public String label;
    }

    public static class Trip {
        public long id, startTs, endTs;
        public double distM, maxKmh;
        public String mode;
    }

    public Store(Context ctx) {
        super(ctx.getApplicationContext(), "movelog.db", null, 5);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE points(ts INTEGER, lat REAL, lon REAL, speed REAL, acc REAL)");
        db.execSQL("CREATE TABLE stays(id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER, end_ts INTEGER, lat REAL, lon REAL, label TEXT, open INTEGER)");
        db.execSQL("CREATE TABLE trips(id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER, end_ts INTEGER, dist_m REAL, max_kmh REAL, mode TEXT, open INTEGER)");
        db.execSQL("CREATE TABLE kv(key TEXT PRIMARY KEY, value TEXT)");
        db.execSQL("CREATE TABLE day_summary(day TEXT PRIMARY KEY, steps INTEGER, dist_m REAL, kcal REAL)");
        db.execSQL("CREATE TABLE hourly(day TEXT, hour INTEGER, steps INTEGER, dist_m REAL, kcal REAL, PRIMARY KEY(day, hour))");
        db.execSQL("CREATE TABLE private_zones(id INTEGER PRIMARY KEY AUTOINCREMENT, lat REAL, lon REAL, radius_m REAL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS day_summary(day TEXT PRIMARY KEY, steps INTEGER, dist_m REAL, kcal REAL)");
        }
        if (oldV < 3) {
            try {
                db.execSQL("ALTER TABLE day_summary ADD COLUMN kcal REAL");
            } catch (Exception ignored) {
            }
        }
        if (oldV < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS hourly(day TEXT, hour INTEGER, steps INTEGER, dist_m REAL, kcal REAL, PRIMARY KEY(day, hour))");
        }
        if (oldV < 5) {
            db.execSQL("CREATE TABLE IF NOT EXISTS private_zones(id INTEGER PRIMARY KEY AUTOINCREMENT, lat REAL, lon REAL, radius_m REAL)");
        }
    }

    // ---- private zones (home etc.: masked, never linked to a map) ----

    public static class Zone {
        public long id;
        public double lat, lon, radiusM;
    }

    public void addPrivateZone(double lat, double lon, double radiusM) {
        ContentValues v = new ContentValues();
        v.put("lat", lat);
        v.put("lon", lon);
        v.put("radius_m", radiusM);
        getWritableDatabase().insert("private_zones", null, v);
    }

    public java.util.List<Zone> privateZones() {
        java.util.List<Zone> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, lat, lon, radius_m FROM private_zones", null);
        while (c.moveToNext()) {
            Zone z = new Zone();
            z.id = c.getLong(0);
            z.lat = c.getDouble(1);
            z.lon = c.getDouble(2);
            z.radiusM = c.getDouble(3);
            out.add(z);
        }
        c.close();
        return out;
    }

    /** Id of the zone containing this point, or -1. */
    public long zoneIdAt(double lat, double lon) {
        for (Zone z : privateZones()) {
            double dLat = (lat - z.lat) * 111_320.0;
            double dLon = (lon - z.lon) * 111_320.0 * Math.cos(Math.toRadians(z.lat));
            if (Math.hypot(dLat, dLon) <= z.radiusM) return z.id;
        }
        return -1;
    }

    public boolean isPrivate(double lat, double lon) {
        return zoneIdAt(lat, lon) >= 0;
    }

    public void removePrivateZone(long id) {
        getWritableDatabase().delete("private_zones", "id=?",
                new String[]{String.valueOf(id)});
    }

    // ---- hourly buckets (day chart) ----

    /** Adds locally-sensed steps to one hour bucket. */
    public void addHourlySteps(String day, int hour, long delta) {
        if (delta <= 0) return;
        SQLiteDatabase db = getWritableDatabase();
        long cur = 0;
        Cursor c = db.rawQuery("SELECT steps FROM hourly WHERE day=? AND hour=?",
                new String[]{day, String.valueOf(hour)});
        if (c.moveToFirst()) cur = c.isNull(0) ? 0 : c.getLong(0);
        c.close();
        ContentValues v = new ContentValues();
        v.put("day", day);
        v.put("hour", hour);
        v.put("steps", cur + delta);
        db.insertWithOnConflict("hourly", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Max-merges Health Connect hourly aggregates into the buckets. */
    public void mergeHourly(String day, int hour, long steps, double distM, double kcal) {
        SQLiteDatabase db = getWritableDatabase();
        long curS = -1;
        double curD = -1, curK = -1;
        Cursor c = db.rawQuery("SELECT steps, dist_m, kcal FROM hourly WHERE day=? AND hour=?",
                new String[]{day, String.valueOf(hour)});
        if (c.moveToFirst()) {
            curS = c.isNull(0) ? -1 : c.getLong(0);
            curD = c.isNull(1) ? -1 : c.getDouble(1);
            curK = c.isNull(2) ? -1 : c.getDouble(2);
        }
        c.close();
        ContentValues v = new ContentValues();
        v.put("day", day);
        v.put("hour", hour);
        v.put("steps", Math.max(curS, steps));
        v.put("dist_m", Math.max(curD, distM));
        v.put("kcal", Math.max(curK, kcal));
        db.insertWithOnConflict("hourly", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 24 buckets for a day: [hour][steps, distM, kcal]; missing = 0/-1/-1. */
    public double[][] hourlyFor(String day) {
        double[][] out = new double[24][3];
        for (int h = 0; h < 24; h++) {
            out[h][0] = 0;
            out[h][1] = -1;
            out[h][2] = -1;
        }
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT hour, steps, dist_m, kcal FROM hourly WHERE day=?",
                new String[]{day});
        while (c.moveToNext()) {
            int h = c.getInt(0);
            if (h < 0 || h > 23) continue;
            out[h][0] = c.isNull(1) ? 0 : c.getLong(1);
            out[h][1] = c.isNull(2) ? -1 : c.getDouble(2);
            out[h][2] = c.isNull(3) ? -1 : c.getDouble(3);
        }
        c.close();
        return out;
    }

    // ---- day summaries (Health Connect import + own step rollover) ----

    public static class DaySummary {
        public String day;
        public long steps = -1;
        public double distM = -1;
        public double kcal = -1;
    }

    /**
     * Merges values into the day's summary, keeping the larger value per
     * field (own step counts and Health Connect aggregates describe the
     * same day; the bigger number is the more complete one). Pass a
     * negative value to leave a field untouched.
     */
    public void upsertDaySummaryMax(String day, long steps, double distM, double kcal) {
        SQLiteDatabase db = getWritableDatabase();
        long curSteps = -1;
        double curDist = -1, curKcal = -1;
        Cursor c = db.rawQuery("SELECT steps, dist_m, kcal FROM day_summary WHERE day=?",
                new String[]{day});
        if (c.moveToFirst()) {
            curSteps = c.getLong(0);
            curDist = c.isNull(1) ? -1 : c.getDouble(1);
            curKcal = c.isNull(2) ? -1 : c.getDouble(2);
        }
        c.close();
        ContentValues v = new ContentValues();
        v.put("day", day);
        v.put("steps", Math.max(curSteps, steps));
        v.put("dist_m", Math.max(curDist, distM));
        v.put("kcal", Math.max(curKcal, kcal));
        db.insertWithOnConflict("day_summary", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Day summaries keyed by yyyy-MM-dd for days in [fromDay, toDay]. */
    public java.util.Map<String, DaySummary> daySummaries(String fromDay, String toDay) {
        java.util.Map<String, DaySummary> out = new java.util.HashMap<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT day, steps, dist_m, kcal FROM day_summary WHERE day>=? AND day<=?",
                new String[]{fromDay, toDay});
        while (c.moveToNext()) {
            DaySummary s = new DaySummary();
            s.day = c.getString(0);
            s.steps = c.isNull(1) ? -1 : c.getLong(1);
            s.distM = c.isNull(2) ? -1 : c.getDouble(2);
            s.kcal = c.isNull(3) ? -1 : c.getDouble(3);
            out.put(s.day, s);
        }
        c.close();
        return out;
    }

    /** @return steps for the day, or -1 when no imported summary exists. */
    public long daySummarySteps(String day) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT steps FROM day_summary WHERE day=?", new String[]{day});
        long out = -1;
        if (c.moveToFirst()) out = c.getLong(0);
        c.close();
        return out;
    }

    /** @return imported distance (m) for the day, or -1 when none exists. */
    public double daySummaryDist(String day) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT dist_m FROM day_summary WHERE day=?", new String[]{day});
        double out = -1;
        if (c.moveToFirst()) out = c.getDouble(0);
        c.close();
        return out;
    }

    // ---- points ----

    public void addPoint(long ts, double lat, double lon, float speedMps, float acc) {
        ContentValues v = new ContentValues();
        v.put("ts", ts);
        v.put("lat", lat);
        v.put("lon", lon);
        v.put("speed", (double) speedMps);
        v.put("acc", (double) acc);
        getWritableDatabase().insert("points", null, v);
    }

    public void prunePoints(long beforeTs) {
        getWritableDatabase().delete("points", "ts < ?", new String[]{String.valueOf(beforeTs)});
    }

    // ---- stays ----

    public long openStay(long startTs, double lat, double lon) {
        ContentValues v = new ContentValues();
        v.put("start_ts", startTs);
        v.put("end_ts", startTs);
        v.put("lat", lat);
        v.put("lon", lon);
        v.put("label", "");
        v.put("open", 1);
        return getWritableDatabase().insert("stays", null, v);
    }

    public void updateStay(long id, long endTs, double lat, double lon) {
        ContentValues v = new ContentValues();
        v.put("end_ts", endTs);
        v.put("lat", lat);
        v.put("lon", lon);
        getWritableDatabase().update("stays", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** Auto-labels only fill an empty label, so a manual rename is never overwritten. */
    public void setStayLabel(long id, String label) {
        ContentValues v = new ContentValues();
        v.put("label", label);
        getWritableDatabase().update("stays", v, "id=? AND (label IS NULL OR label='')",
                new String[]{String.valueOf(id)});
    }

    /** Rename this stay and any other stays at (nearly) the same place. Returns count. */
    public int renameStaysNear(double lat, double lon, double radiusM, String label) {
        int count = 0;
        Cursor c = getReadableDatabase().rawQuery("SELECT id, lat, lon FROM stays", null);
        java.util.List<Long> ids = new ArrayList<>();
        while (c.moveToNext()) {
            double dLat = (c.getDouble(1) - lat) * 111_320.0;
            double dLon = (c.getDouble(2) - lon) * 111_320.0 * Math.cos(Math.toRadians(lat));
            if (Math.hypot(dLat, dLon) <= radiusM) ids.add(c.getLong(0));
        }
        c.close();
        ContentValues v = new ContentValues();
        v.put("label", label);
        for (long id : ids) {
            count += getWritableDatabase().update("stays", v, "id=?",
                    new String[]{String.valueOf(id)});
        }
        return count;
    }

    public void closeStay(long id, long endTs) {
        ContentValues v = new ContentValues();
        v.put("end_ts", endTs);
        v.put("open", 0);
        getWritableDatabase().update("stays", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteStay(long id) {
        getWritableDatabase().delete("stays", "id=?", new String[]{String.valueOf(id)});
    }

    public Stay getOpenStay() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,start_ts,end_ts,lat,lon,label FROM stays WHERE open=1 ORDER BY id DESC LIMIT 1", null);
        Stay s = null;
        if (c.moveToFirst()) {
            s = new Stay();
            s.id = c.getLong(0);
            s.startTs = c.getLong(1);
            s.endTs = c.getLong(2);
            s.lat = c.getDouble(3);
            s.lon = c.getDouble(4);
            s.label = c.getString(5);
        }
        c.close();
        return s;
    }

    // ---- trips ----

    public long openTrip(long startTs) {
        ContentValues v = new ContentValues();
        v.put("start_ts", startTs);
        v.put("end_ts", startTs);
        v.put("dist_m", 0.0);
        v.put("max_kmh", 0.0);
        v.put("mode", "");
        v.put("open", 1);
        return getWritableDatabase().insert("trips", null, v);
    }

    public void updateTrip(long id, long endTs, double distM, double maxKmh) {
        ContentValues v = new ContentValues();
        v.put("end_ts", endTs);
        v.put("dist_m", distM);
        v.put("max_kmh", maxKmh);
        getWritableDatabase().update("trips", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void closeTrip(long id, long endTs, double distM, double maxKmh, String mode) {
        ContentValues v = new ContentValues();
        v.put("end_ts", endTs);
        v.put("dist_m", distM);
        v.put("max_kmh", maxKmh);
        v.put("mode", mode);
        v.put("open", 0);
        getWritableDatabase().update("trips", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteTrip(long id) {
        getWritableDatabase().delete("trips", "id=?", new String[]{String.valueOf(id)});
    }

    public Trip getOpenTrip() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,start_ts,end_ts,dist_m,max_kmh,mode FROM trips WHERE open=1 ORDER BY id DESC LIMIT 1", null);
        Trip t = null;
        if (c.moveToFirst()) {
            t = new Trip();
            t.id = c.getLong(0);
            t.startTs = c.getLong(1);
            t.endTs = c.getLong(2);
            t.distM = c.getDouble(3);
            t.maxKmh = c.getDouble(4);
            t.mode = c.getString(5);
        }
        c.close();
        return t;
    }

    /** Close any segment left open by a previous process, at the given time. */
    public void closeDangling(long ts) {
        Stay s = getOpenStay();
        if (s != null) closeStay(s.id, Math.max(ts, s.startTs));
        Trip t = getOpenTrip();
        if (t != null) closeTrip(t.id, Math.max(ts, t.startTs), t.distM, t.maxKmh, modeFor(t.maxKmh));
    }

    public static String modeFor(double maxKmh) {
        if (maxKmh < 8) return "徒歩";
        if (maxKmh < 28) return "自転車など";
        if (maxKmh < 75) return "車・電車";
        if (maxKmh < 160) return "高速移動（車・特急など）";
        return "新幹線などの高速移動";
    }

    // ---- day queries ----

    public List<Stay> staysIn(long fromTs, long toTs) {
        List<Stay> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,start_ts,end_ts,lat,lon,label FROM stays WHERE end_ts>=? AND start_ts<=? ORDER BY start_ts",
                new String[]{String.valueOf(fromTs), String.valueOf(toTs)});
        while (c.moveToNext()) {
            Stay s = new Stay();
            s.id = c.getLong(0);
            s.startTs = c.getLong(1);
            s.endTs = c.getLong(2);
            s.lat = c.getDouble(3);
            s.lon = c.getDouble(4);
            s.label = c.getString(5);
            out.add(s);
        }
        c.close();
        return out;
    }

    public List<Trip> tripsIn(long fromTs, long toTs) {
        List<Trip> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,start_ts,end_ts,dist_m,max_kmh,mode FROM trips WHERE end_ts>=? AND start_ts<=? ORDER BY start_ts",
                new String[]{String.valueOf(fromTs), String.valueOf(toTs)});
        while (c.moveToNext()) {
            Trip t = new Trip();
            t.id = c.getLong(0);
            t.startTs = c.getLong(1);
            t.endTs = c.getLong(2);
            t.distM = c.getDouble(3);
            t.maxKmh = c.getDouble(4);
            t.mode = c.getString(5);
            out.add(t);
        }
        c.close();
        return out;
    }

    public double distanceIn(long fromTs, long toTs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(dist_m),0) FROM trips WHERE end_ts>=? AND start_ts<=?",
                new String[]{String.valueOf(fromTs), String.valueOf(toTs)});
        double d = 0;
        if (c.moveToFirst()) d = c.getDouble(0);
        c.close();
        return d;
    }

    // ---- kv ----

    public String getKv(String key, String def) {
        Cursor c = getReadableDatabase().rawQuery("SELECT value FROM kv WHERE key=?", new String[]{key});
        String v = def;
        if (c.moveToFirst()) v = c.getString(0);
        c.close();
        return v;
    }

    public void setKv(String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("value", value);
        getWritableDatabase().insertWithOnConflict("kv", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
