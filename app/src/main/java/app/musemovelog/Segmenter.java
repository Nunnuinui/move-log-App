package app.musemovelog;

import android.location.Location;

/**
 * Turns a stream of location fixes into stays and trips.
 * A stay is a cluster of fixes within ~100 m; leaving the cluster by more
 * than ~160 m starts a trip; stopping inside ~60 m for 3 minutes ends it.
 */
public class Segmenter {

    public interface Listener {
        void onStayKept(long stayId, double lat, double lon);

        void onSegmentChanged();
    }

    static final double STAY_RADIUS_M = 100;
    static final double EXIT_RADIUS_M = 160;
    static final long MIN_STAY_MS = 8 * 60_000L;
    static final long STOP_MS = 3 * 60_000L;
    static final double STOP_RADIUS_M = 60;
    static final float MAX_ACC_M = 80f;
    static final double MAX_PLAUSIBLE_KMH = 400;

    private final Store store;
    private final Listener listener;

    private long stayId = -1;
    private double cLat, cLon;
    private int cCount;
    private long stayStart;

    private long tripId = -1;
    private double tripDist, tripMaxKmh;
    private double stopLat, stopLon;
    private long stopSince = -1;

    private Location lastFix;

    public Segmenter(Store store, Listener listener) {
        this.store = store;
        this.listener = listener;
    }

    public boolean isInTrip() {
        return tripId != -1;
    }

    public synchronized void onFix(Location f) {
        if (f.getAccuracy() > MAX_ACC_M && lastFix != null) return;

        if (stayId == -1 && tripId == -1) {
            beginStay(f);
            lastFix = copy(f);
            return;
        }

        if (stayId != -1) {
            double d = distance(cLat, cLon, f.getLatitude(), f.getLongitude());
            if (d <= STAY_RADIUS_M) {
                // Weighted centroid drift toward the new fix.
                cCount++;
                cLat += (f.getLatitude() - cLat) / Math.min(cCount, 20);
                cLon += (f.getLongitude() - cLon) / Math.min(cCount, 20);
                store.updateStay(stayId, f.getTime(), cLat, cLon);
            } else if (d > EXIT_RADIUS_M) {
                endStay(f.getTime());
                beginTrip(f, d);
            }
            // Between the radii: treat as noise, stay continues.
        } else if (tripId != -1) {
            double seg = distance(lastFix.getLatitude(), lastFix.getLongitude(),
                    f.getLatitude(), f.getLongitude());
            double dtSec = Math.max(1, (f.getTime() - lastFix.getTime()) / 1000.0);
            double impliedKmh = seg / dtSec * 3.6;
            if (impliedKmh <= MAX_PLAUSIBLE_KMH) {
                tripDist += seg;
                double kmh = f.hasSpeed() ? f.getSpeed() * 3.6 : impliedKmh;
                if (kmh <= 380 && kmh > tripMaxKmh) tripMaxKmh = kmh;
            }
            store.updateTrip(tripId, f.getTime(), tripDist, tripMaxKmh);

            // Stop detection around a slowly-moving anchor.
            if (stopSince == -1) {
                stopLat = f.getLatitude();
                stopLon = f.getLongitude();
                stopSince = f.getTime();
            } else if (distance(stopLat, stopLon, f.getLatitude(), f.getLongitude()) > STOP_RADIUS_M) {
                stopLat = f.getLatitude();
                stopLon = f.getLongitude();
                stopSince = f.getTime();
            } else if (f.getTime() - stopSince >= STOP_MS) {
                endTrip(stopSince);
                beginStayAt(stopSince, stopLat, stopLon);
            }
        }
        lastFix = copy(f);
    }

    /** Close whatever segment is open (service stopping). */
    public synchronized void closeCurrent(long ts) {
        if (stayId != -1) endStay(ts);
        if (tripId != -1) endTrip(ts);
        lastFix = null;
    }

    private void beginStay(Location f) {
        beginStayAt(f.getTime(), f.getLatitude(), f.getLongitude());
    }

    private void beginStayAt(long ts, double lat, double lon) {
        stayId = store.openStay(ts, lat, lon);
        cLat = lat;
        cLon = lon;
        cCount = 1;
        stayStart = ts;
        stopSince = -1;
        listener.onSegmentChanged();
    }

    private void endStay(long ts) {
        long id = stayId;
        stayId = -1;
        if (ts - stayStart >= MIN_STAY_MS) {
            store.closeStay(id, ts);
            listener.onStayKept(id, cLat, cLon);
        } else {
            store.deleteStay(id);
        }
        listener.onSegmentChanged();
    }

    private void beginTrip(Location f, double firstSegM) {
        tripId = store.openTrip(f.getTime());
        tripDist = firstSegM;
        tripMaxKmh = f.hasSpeed() ? f.getSpeed() * 3.6 : 0;
        stopSince = -1;
        store.updateTrip(tripId, f.getTime(), tripDist, tripMaxKmh);
        listener.onSegmentChanged();
    }

    private void endTrip(long ts) {
        long id = tripId;
        double dist = tripDist, max = tripMaxKmh;
        tripId = -1;
        tripDist = 0;
        tripMaxKmh = 0;
        if (dist >= 50) {
            store.closeTrip(id, ts, dist, max, Store.modeFor(max));
        } else {
            store.deleteTrip(id);
        }
        listener.onSegmentChanged();
    }

    static double distance(double lat1, double lon1, double lat2, double lon2) {
        float[] out = new float[1];
        Location.distanceBetween(lat1, lon1, lat2, lon2, out);
        return out[0];
    }

    private static Location copy(Location f) {
        return new Location(f);
    }
}
