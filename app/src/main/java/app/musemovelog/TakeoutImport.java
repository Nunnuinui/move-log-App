package app.musemovelog;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports Google Fit data exported via Google Takeout. Accepts the whole
 * Takeout ZIP or individual "Daily activity metrics" CSV files
 * (YYYY-MM-DD.csv, possibly several segment rows per day). Columns are
 * detected by header names in English or Japanese.
 */
public class TakeoutImport {

    public interface Callback {
        void onDone(int days, String error);
    }

    private static final Pattern DATE_IN_NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    public static void importUris(Context ctx, List<Uri> uris, Store store, Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Map<String, double[]> agg = new HashMap<>(); // day -> [steps, distM, kcal]
            String error = null;
            try {
                for (Uri uri : uris) {
                    String name = queryName(ctx, uri);
                    InputStream raw = ctx.getContentResolver().openInputStream(uri);
                    if (raw == null) continue;
                    PushbackInputStream in = new PushbackInputStream(raw, 2);
                    byte[] head = new byte[2];
                    int n = in.read(head);
                    if (n > 0) in.unread(head, 0, n);
                    boolean looksZip = n == 2 && head[0] == 'P' && head[1] == 'K';
                    if (looksZip || (name != null && name.toLowerCase().endsWith(".zip"))) {
                        ZipInputStream zis = new ZipInputStream(in);
                        ZipEntry e;
                        while ((e = zis.getNextEntry()) != null) {
                            String en = e.getName();
                            if (!e.isDirectory() && en.toLowerCase().endsWith(".csv")) {
                                parseCsv(zis, dayFromName(en), agg);
                            }
                            zis.closeEntry();
                        }
                        // ZipInputStream is consumed; don't close "in" twice.
                    } else {
                        parseCsv(in, name != null ? dayFromName(name) : null, agg);
                        in.close();
                    }
                }
            } catch (Exception e) {
                Dbg.log(ctx, "takeout import failed: " + e);
                error = e.getMessage();
            }
            int count = 0;
            if (error == null) {
                for (Map.Entry<String, double[]> en : agg.entrySet()) {
                    double[] v = en.getValue();
                    if (v[0] > 0 || v[1] > 0 || v[2] > 0) {
                        store.upsertDaySummaryMax(en.getKey(),
                                Math.round(v[0]), v[1], v[2]);
                        count++;
                    }
                }
            }
            final int fCount = count;
            final String fError = error;
            main.post(() -> cb.onDone(fCount, fError));
        }).start();
    }

    private static String queryName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    private static String dayFromName(String name) {
        if (name == null) return null;
        Matcher m = DATE_IN_NAME.matcher(name);
        return m.find() ? m.group(1) : null;
    }

    private static void parseCsv(InputStream in, String fallbackDay,
                                 Map<String, double[]> agg) {
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String headerLine = null;
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    headerLine = line;
                    break;
                }
            }
            if (headerLine == null) return;
            List<String> headers = splitCsv(headerLine.replace("\uFEFF", ""));
            int iSteps = find(headers, "Steps", "歩数", "step");
            int iDistM = find(headers, "Distance (m)", "距離 (m)");
            int iDistKm = find(headers, "Distance (km)", "距離 (km)");
            int iDist = iDistM >= 0 ? iDistM : iDistKm;
            double distScale = iDistKm >= 0 && iDistM < 0 ? 1000.0 : 1.0;
            if (iDist < 0) iDist = find(headers, "Distance", "距離");
            int iKcal = find(headers, "Calories", "カロリー");
            int iStart = find(headers, "Start time", "開始");
            if (iSteps < 0 && iDist < 0 && iKcal < 0) return; // not an activity CSV

            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                List<String> cols = splitCsv(line);
                String day = fallbackDay;
                if (day == null && iStart >= 0 && cols.size() > iStart) {
                    String s = cols.get(iStart).trim();
                    if (s.length() >= 10 && s.charAt(4) == '-') day = s.substring(0, 10);
                }
                if (day == null) continue;
                double[] v = agg.computeIfAbsent(day, k -> new double[3]);
                if (iSteps >= 0) v[0] += num(cols, iSteps);
                if (iDist >= 0) v[1] += num(cols, iDist) * distScale;
                if (iKcal >= 0) v[2] += num(cols, iKcal);
            }
        } catch (Exception e) {
            // A single unreadable CSV must not sink the whole import.
        }
    }

    private static int find(List<String> headers, String... aliases) {
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i).trim().toLowerCase();
            for (String a : aliases) {
                if (h.contains(a.toLowerCase())) return i;
            }
        }
        return -1;
    }

    private static double num(List<String> cols, int idx) {
        if (idx < 0 || cols.size() <= idx) return 0;
        String s = cols.get(idx).replace(",", "").replace(" ", "").trim();
        if (s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Minimal CSV splitter honoring double quotes. */
    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
