package app.musemovelog;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATION = 1003;

    private static final int METRIC_STEPS = 0;
    private static final int METRIC_DISTANCE = 1;
    private static final int METRIC_KCAL = 2;
    private static final int[] METRIC_COLORS = {0xFF8AB4F8, 0xFF7ED491, 0xFFF2A65A};

    private Store store;
    private TextView summaryTitle, sumSteps, sumDistance, sumCounts, dayLabel;
    private LinearLayout timeline;
    private int dayOffset = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 30_000);
        }
    };

    // Embedded calendar state
    private static final int MODE_DAY = 0;
    private static final int MODE_WEEK = 1;
    private static final int MODE_MONTH = 2;
    private int calMetric = METRIC_STEPS;
    private int calMode = MODE_MONTH;
    private Calendar calCursor;
    private Button calChipSteps, calChipDistance, calChipKcal, calTabDay, calTabMonth, calTabWeek;
    private TextView calPeriod, calTotal, calWeeklyTitle;
    private LinearLayout calMonth, calWeek, calDay, calWeeklyRows;
    private LinearLayout calDayChart, calDayAxis, calDayDetail;
    private final java.util.Set<String> hourlyFetched = new java.util.HashSet<>();
    private int selectedHour = -1;
    private String lastDayKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View scroll = findViewById(R.id.main_scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars =
                    insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        store = new Store(this);

        summaryTitle = findViewById(R.id.summary_title);
        sumSteps = findViewById(R.id.sum_steps);
        sumDistance = findViewById(R.id.sum_distance);
        sumCounts = findViewById(R.id.sum_counts);
        dayLabel = findViewById(R.id.day_label);
        timeline = findViewById(R.id.timeline);

        calMetric = getSharedPreferences("calendar", MODE_PRIVATE).getInt("metric", METRIC_STEPS);
        calMode = getSharedPreferences("calendar", MODE_PRIVATE).getInt("cal_mode", MODE_MONTH);
        calCursor = Calendar.getInstance();
        calCursor.set(Calendar.HOUR_OF_DAY, 0);
        calCursor.set(Calendar.MINUTE, 0);
        calCursor.set(Calendar.SECOND, 0);
        calCursor.set(Calendar.MILLISECOND, 0);
        calChipSteps = findViewById(R.id.cal_chip_steps);
        calChipDistance = findViewById(R.id.cal_chip_distance);
        calChipKcal = findViewById(R.id.cal_chip_kcal);
        calTabDay = findViewById(R.id.cal_tab_day);
        calTabMonth = findViewById(R.id.cal_tab_month);
        calTabWeek = findViewById(R.id.cal_tab_week);
        calPeriod = findViewById(R.id.cal_period);
        calTotal = findViewById(R.id.cal_total);
        calWeeklyTitle = findViewById(R.id.cal_weekly_title);
        calDayChart = findViewById(R.id.cal_day_chart);
        calDayAxis = findViewById(R.id.cal_day_axis);
        calDayDetail = findViewById(R.id.cal_day_detail);
        calMonth = findViewById(R.id.cal_month);
        calWeek = findViewById(R.id.cal_week);
        calDay = findViewById(R.id.cal_day);
        calWeeklyRows = findViewById(R.id.cal_weekly_rows);

        calChipSteps.setOnClickListener(v -> setCalMetric(METRIC_STEPS));
        calChipDistance.setOnClickListener(v -> setCalMetric(METRIC_DISTANCE));
        calChipKcal.setOnClickListener(v -> setCalMetric(METRIC_KCAL));
        calTabDay.setOnClickListener(v -> setCalMode(MODE_DAY));
        calTabWeek.setOnClickListener(v -> setCalMode(MODE_WEEK));
        calTabMonth.setOnClickListener(v -> setCalMode(MODE_MONTH));
        findViewById(R.id.cal_prev).setOnClickListener(v -> calNavigate(-1));
        findViewById(R.id.cal_next).setOnClickListener(v -> calNavigate(1));

        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_prev_day).setOnClickListener(v -> {
            dayOffset--;
            refresh();
        });
        findViewById(R.id.btn_next_day).setOnClickListener(v -> {
            if (dayOffset < 0) dayOffset++;
            refresh();
        });

        // If tracking was on but the service died (e.g. app update), restart it.
        if (TrackingService.isEnabled(this) && hasFineLocation()) {
            TrackingService.start(this);
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        handler.postDelayed(refresher, 30_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private boolean hasFineLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refresh() {
        refreshDaySection();
        renderCalendar();
    }

    private void refreshDaySection() {
        long dayStart = TrackingService.dayStart(System.currentTimeMillis())
                + dayOffset * 24L * 3600_000L;
        long dayEnd = dayStart + 24L * 3600_000L;
        SimpleDateFormat dayFmt = new SimpleDateFormat("M月d日(E)", Locale.JAPAN);
        String dayText = dayOffset == 0 ? "今日（" + dayFmt.format(dayStart) + "）" : dayFmt.format(dayStart);
        dayLabel.setText(dayText);
        summaryTitle.setText(dayOffset == 0 ? "今日のまとめ" : dayFmt.format(dayStart) + "のまとめ");

        String dayKey = TrackingService.dayKey(dayStart);
        List<Store.Trip> trips = store.tripsIn(dayStart, dayEnd);
        List<Store.Stay> stays = store.staysIn(dayStart, dayEnd);
        if (dayOffset == 0) {
            sumSteps.setText(String.format(Locale.JAPAN, "%,d", TrackingService.stepsToday(this)));
            sumDistance.setText(String.format(Locale.JAPAN, "%.1f km",
                    store.distanceIn(dayStart, dayEnd) / 1000.0));
        } else {
            long steps = store.daySummarySteps(dayKey);
            sumSteps.setText(steps >= 0 ? String.format(Locale.JAPAN, "%,d", steps) : "—");
            double tripDist = store.distanceIn(dayStart, dayEnd);
            double impDist = store.daySummaryDist(dayKey);
            double dist = tripDist > 0 ? tripDist : (impDist >= 0 ? impDist : 0);
            sumDistance.setText(String.format(Locale.JAPAN, "%.1f km", dist / 1000.0));
        }
        sumCounts.setText(trips.size() + " · " + stays.size());

        buildTimeline(trips, stays);
    }

    private void buildTimeline(List<Store.Trip> trips, List<Store.Stay> stays) {
        timeline.removeAllViews();
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.JAPAN);
        List<Object> items = new ArrayList<>();
        items.addAll(stays);
        items.addAll(trips);
        items.sort(Comparator.comparingLong(o -> o instanceof Store.Stay
                ? ((Store.Stay) o).startTs : ((Store.Trip) o).startTs));

        int shown = 0;
        for (Object o : items) {
            if (o instanceof Store.Stay) {
                Store.Stay s = (Store.Stay) o;
                long durMin = (s.endTs - s.startTs) / 60_000;
                if (durMin < 3 && s.endTs != s.startTs) continue;
                String text = "滞在　" + stayLabel(s) + "\n"
                        + tf.format(s.startTs) + " – " + tf.format(s.endTs)
                        + "（" + durMin + "分）";
                TextView row = entryView(text);
                row.setOnClickListener(v -> onStayTap(s));
                timeline.addView(row);
                shown++;
            } else {
                Store.Trip t = (Store.Trip) o;
                String mode = (t.mode != null && !t.mode.isEmpty())
                        ? t.mode : Store.modeFor(t.maxKmh);
                String text = "移動　" + mode + "\n"
                        + tf.format(t.startTs) + " – " + tf.format(t.endTs)
                        + String.format(Locale.JAPAN, "　%.1f km　最高 %.0f km/h",
                        t.distM / 1000.0, t.maxKmh);
                timeline.addView(entryView(text));
                shown++;
            }
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText(dayOffset == 0
                    ? "この日の記録はまだありません"
                    : "この日の記録はありません（歩数・距離は上のまとめに表示されます）");
            empty.setTextColor(0xFF9AA0A6);
            empty.setTextSize(13);
            empty.setPadding(4, 12, 4, 12);
            timeline.addView(empty);
        }
    }

    /** Display label for a stay; private-zone stays are always masked. */
    private String stayLabel(Store.Stay s) {
        if (store.isPrivate(s.lat, s.lon)) return "非表示の場所";
        return (s.label != null && !s.label.isEmpty()) ? s.label : "地点";
    }

    private void onStayTap(Store.Stay s) {
        long zoneId = store.zoneIdAt(s.lat, s.lon);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        if (zoneId >= 0) {
            b.setTitle("非表示の場所");
            b.setItems(new String[]{"非表示を解除する"}, (d, which) -> {
                store.removePrivateZone(zoneId);
                android.widget.Toast.makeText(this, "非表示を解除しました",
                        android.widget.Toast.LENGTH_SHORT).show();
                refresh();
            });
        } else {
            b.setTitle(stayLabel(s));
            b.setItems(new String[]{"Google マップで開く", "名前を編集する", "この場所を非表示に登録する"}, (d, which) -> {
                if (which == 0) {
                    String url = "https://www.google.com/maps/search/?api=1&query="
                            + s.lat + "," + s.lon;
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse(url)));
                    } catch (Exception e) {
                        android.widget.Toast.makeText(this, "マップを開けませんでした",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                } else if (which == 1) {
                    showRenameDialog(s);
                } else {
                    store.addPrivateZone(s.lat, s.lon, 300);
                    android.widget.Toast.makeText(this,
                            "半径300mを非表示に登録しました（自宅用）",
                            android.widget.Toast.LENGTH_LONG).show();
                    refresh();
                }
            });
        }
        b.setNegativeButton("キャンセル", null);
        b.show();
    }

    private void showRenameDialog(Store.Stay s) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(s.label != null ? s.label : "");
        input.setSelection(input.getText().length());
        input.setHint("例：職場、実家、〇〇駅");
        int pad = dp(20);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.setPadding(pad, dp(8), pad, 0);
        wrap.addView(input);
        new android.app.AlertDialog.Builder(this)
                .setTitle("地点の名前")
                .setView(wrap)
                .setPositiveButton("保存", (d, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        android.widget.Toast.makeText(this, "名前を入力してください",
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int n = store.renameStaysNear(s.lat, s.lon, 150, name);
                    android.widget.Toast.makeText(this,
                            "「" + name + "」を " + n + " 件の滞在に付けました",
                            android.widget.Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private TextView entryView(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFF2F3F5);
        tv.setTextSize(14);
        tv.setLineSpacing(1.25f, 1f);
        tv.setPadding(18, 14, 18, 14);
        tv.setBackgroundResource(R.drawable.section_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 10;
        tv.setLayoutParams(lp);
        return tv;
    }

    // ---- embedded calendar ----

    private void setCalMetric(int m) {
        calMetric = m;
        getSharedPreferences("calendar", MODE_PRIVATE).edit().putInt("metric", m).apply();
        renderCalendar();
    }

    private void setCalMode(int mode) {
        calMode = mode;
        getSharedPreferences("calendar", MODE_PRIVATE).edit().putInt("cal_mode", mode).apply();
        if (mode == MODE_DAY) {
            // Day mode starts from the day shown in the summary/timeline.
            calCursor.setTimeInMillis(TrackingService.dayStart(System.currentTimeMillis())
                    + dayOffset * 24L * 3600_000L);
        }
        renderCalendar();
    }

    private void calNavigate(int dir) {
        switch (calMode) {
            case MODE_DAY:
                calCursor.add(Calendar.DAY_OF_MONTH, dir);
                break;
            case MODE_WEEK:
                calCursor.add(Calendar.WEEK_OF_YEAR, dir);
                break;
            default:
                calCursor.add(Calendar.MONTH, dir);
                break;
        }
        if (calMode == MODE_DAY) {
            // Keep the summary/timeline in sync with the day being viewed.
            long todayStart = TrackingService.dayStart(System.currentTimeMillis());
            long diff = (TrackingService.dayStart(calCursor.getTimeInMillis()) - todayStart)
                    / (24L * 3600_000L);
            if (diff <= 0) {
                dayOffset = (int) diff;
                refreshDaySection();
            }
        }
        renderCalendar();
    }

    private void selectDay(long dayStartMs) {
        long todayStart = TrackingService.dayStart(System.currentTimeMillis());
        long diffDays = (TrackingService.dayStart(dayStartMs) - todayStart) / (24L * 3600_000L);
        if (diffDays <= 0) {
            dayOffset = (int) diffDays;
            calCursor.setTimeInMillis(TrackingService.dayStart(dayStartMs));
            refresh();
        }
    }

    private double calValueFor(String dayKey, Map<String, Store.DaySummary> sums,
                               Map<String, Double> tripDistByDay) {
        return valueForMetric(calMetric, dayKey, sums, tripDistByDay);
    }

    private double valueForMetric(int metric, String dayKey, Map<String, Store.DaySummary> sums,
                                  Map<String, Double> tripDistByDay) {
        Store.DaySummary s = sums.get(dayKey);
        switch (metric) {
            case METRIC_STEPS: {
                if (dayKey.equals(TrackingService.dayKey(System.currentTimeMillis()))) {
                    return TrackingService.stepsToday(this);
                }
                return s != null && s.steps >= 0 ? s.steps : -1;
            }
            case METRIC_DISTANCE: {
                double own = tripDistByDay.getOrDefault(dayKey, 0.0);
                double imp = s != null ? s.distM : -1;
                double v = Math.max(own, imp);
                return v > 0 ? v : -1;
            }
            default: {
                return s != null && s.kcal > 0 ? s.kcal : -1;
            }
        }
    }

    private Map<String, Double> tripDistByDay(long fromMs, long toMs) {
        Map<String, Double> out = new HashMap<>();
        for (Store.Trip t : store.tripsIn(fromMs, toMs)) {
            String k = TrackingService.dayKey(t.startTs);
            out.put(k, out.getOrDefault(k, 0.0) + t.distM);
        }
        return out;
    }

    private String calFormat(double v) {
        return formatMetric(calMetric, v);
    }

    private String formatMetric(int metric, double v) {
        if (v < 0) return "—";
        switch (metric) {
            case METRIC_STEPS:
                return String.format(Locale.JAPAN, "%,d 歩", Math.round(v));
            case METRIC_DISTANCE:
                return String.format(Locale.JAPAN, "%.1f km", v / 1000.0);
            default:
                return String.format(Locale.JAPAN, "%,d kcal", Math.round(v));
        }
    }

    private String calShort(double v) {
        switch (calMetric) {
            case METRIC_STEPS:
                return String.format(Locale.JAPAN, "%,d", Math.round(v));
            case METRIC_DISTANCE:
                return String.format(Locale.JAPAN, "%.1f", v / 1000.0);
            default:
                return String.format(Locale.JAPAN, "%,d", Math.round(v));
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void styleChip(Button b, boolean selected) {
        if (selected) {
            b.setBackgroundResource(R.drawable.btn_pill_primary);
        } else {
            b.setBackgroundResource(0);
        }
        b.setTextColor(selected ? 0xFF191423 : 0xFF9AA0A6);
    }

    private void renderCalendar() {
        styleChip(calChipSteps, calMetric == METRIC_STEPS);
        styleChip(calChipDistance, calMetric == METRIC_DISTANCE);
        styleChip(calChipKcal, calMetric == METRIC_KCAL);
        styleChip(calTabDay, calMode == MODE_DAY);
        styleChip(calTabWeek, calMode == MODE_WEEK);
        styleChip(calTabMonth, calMode == MODE_MONTH);
        calMonth.setVisibility(calMode == MODE_MONTH ? View.VISIBLE : View.GONE);
        calWeek.setVisibility(calMode == MODE_WEEK ? View.VISIBLE : View.GONE);
        calDay.setVisibility(calMode == MODE_DAY ? View.VISIBLE : View.GONE);
        calWeeklyTitle.setVisibility(calMode == MODE_MONTH ? View.VISIBLE : View.GONE);
        calWeeklyRows.setVisibility(calMode == MODE_MONTH ? View.VISIBLE : View.GONE);
        switch (calMode) {
            case MODE_DAY:
                renderCalDay();
                break;
            case MODE_WEEK:
                renderCalWeek();
                break;
            default:
                renderCalMonth();
                break;
        }
    }

    private void renderCalDay() {
        long dayStart = TrackingService.dayStart(calCursor.getTimeInMillis());
        long dayEnd = dayStart + 24L * 3600_000L;
        String key = TrackingService.dayKey(dayStart);
        if (!key.equals(lastDayKey)) {
            lastDayKey = key;
            selectedHour = -1;
        }
        Map<String, Store.DaySummary> sums = store.daySummaries(key, key);
        Map<String, Double> tripDistMap = tripDistByDay(dayStart, dayEnd);
        calPeriod.setText(new SimpleDateFormat("M月d日(E)", Locale.JAPAN).format(dayStart));
        // Headline total uses the authoritative daily figure; the bars show
        // how it distributes over the day.
        calTotal.setText("合計 " + formatMetric(calMetric,
                valueForMetric(calMetric, key, sums, tripDistMap)));

        double[][] hourly = store.hourlyFor(key);
        double[] tripByHour = new double[24];
        List<Store.Trip> dayTrips = store.tripsIn(dayStart, dayEnd);
        List<Store.Stay> dayStays = store.staysIn(dayStart, dayEnd);
        for (Store.Trip t : dayTrips) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(t.startTs);
            tripByHour[c.get(Calendar.HOUR_OF_DAY)] += t.distM;
        }
        double[] values = new double[24];
        double max = 0;
        for (int h = 0; h < 24; h++) {
            double v;
            switch (calMetric) {
                case METRIC_STEPS:
                    v = hourly[h][0];
                    break;
                case METRIC_DISTANCE:
                    v = Math.max(hourly[h][1], tripByHour[h]);
                    break;
                default:
                    v = hourly[h][2] > 0 ? hourly[h][2] : 0;
                    break;
            }
            values[h] = v;
            if (v > max) max = v;
        }

        calDayChart.removeAllViews();
        for (int h = 0; h < 24; h++) {
            FrameLayout slot = new FrameLayout(this);
            calDayChart.addView(slot, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            final int fh = h;
            slot.setOnClickListener(view -> {
                selectedHour = fh;
                renderCalDay();
            });
            double v = values[h];
            if (v > 0) {
                View bar = new View(this);
                int bh = Math.max(dp(3), (int) Math.round(dp(140) * (v / Math.max(max, 1))));
                GradientDrawable g = new GradientDrawable();
                g.setColor(METRIC_COLORS[calMetric]);
                g.setCornerRadii(new float[]{dp(3), dp(3), dp(3), dp(3), 0, 0, 0, 0});
                if (h == selectedHour) g.setStroke(dp(2), 0xFFFFFFFF);
                bar.setBackground(g);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(10), bh);
                blp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                slot.addView(bar, blp);
            } else if (h == selectedHour) {
                View mark = new View(this);
                GradientDrawable g = new GradientDrawable();
                g.setColor(0xFF9AA0A6);
                g.setCornerRadii(new float[]{dp(3), dp(3), dp(3), dp(3), 0, 0, 0, 0});
                mark.setBackground(g);
                FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(dp(10), dp(3));
                mlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                slot.addView(mark, mlp);
            }
        }
        calDayAxis.removeAllViews();
        for (int slot = 0; slot < 25; slot++) {
            if (slot % 4 == 0) {
                TextView tv = new TextView(this);
                tv.setText(String.valueOf(slot));
                tv.setTextColor(0xFF9AA0A6);
                tv.setTextSize(9);
                tv.setGravity(Gravity.CENTER);
                calDayAxis.addView(tv, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            } else {
                calDayAxis.addView(new View(this), new LinearLayout.LayoutParams(
                        0, 1, 1));
            }
        }

        buildDayDetail(dayStart, hourly, tripByHour, dayTrips, dayStays);

        // Fill the hourly buckets from Health Connect once per day per session.
        if (!hourlyFetched.contains(key) && HealthImport.isAvailable(this)) {
            hourlyFetched.add(key);
            final String fKey = key;
            HealthImport.importDayHourly(this, store, dayStart, ok -> {
                if (ok && calMode == MODE_DAY
                        && TrackingService.dayKey(
                                TrackingService.dayStart(calCursor.getTimeInMillis())).equals(fKey)) {
                    renderCalDay();
                }
                return kotlin.Unit.INSTANCE;
            });
        }
    }

    private void buildDayDetail(long dayStart, double[][] hourly, double[] tripByHour,
                                List<Store.Trip> trips, List<Store.Stay> stays) {
        calDayDetail.removeAllViews();
        if (selectedHour < 0) {
            calDayDetail.setVisibility(View.GONE);
            return;
        }
        calDayDetail.setVisibility(View.VISIBLE);
        int h = selectedHour;
        long hs = dayStart + h * 3600_000L;
        long he = hs + 3600_000L;
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.JAPAN);

        TextView title = new TextView(this);
        title.setText(String.format(Locale.JAPAN, "%d:00〜%d:00", h, h + 1));
        title.setTextColor(0xFFF2F3F5);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        calDayDetail.addView(title);

        long steps = Math.round(hourly[h][0]);
        double dist = Math.max(hourly[h][1], tripByHour[h]);
        double kcal = hourly[h][2];
        TextView metrics = new TextView(this);
        metrics.setText("歩数 " + String.format(Locale.JAPAN, "%,d", steps) + "歩"
                + "　距離 " + (dist > 0 ? String.format(Locale.JAPAN, "%.1f km", dist / 1000.0) : "—")
                + "　カロリー " + (kcal > 0 ? String.format(Locale.JAPAN, "%,d kcal", Math.round(kcal)) : "—"));
        metrics.setTextColor(0xFFF2F3F5);
        metrics.setTextSize(13);
        metrics.setPadding(0, dp(6), 0, 0);
        calDayDetail.addView(metrics);

        boolean any = steps > 0 || dist > 0 || kcal > 0;
        for (Store.Stay s : stays) {
            if (s.startTs < he && s.endTs > hs) {
                any = true;
                TextView row = new TextView(this);
                row.setText("滞在　" + stayLabel(s) + "　" + tf.format(s.startTs) + " – " + tf.format(s.endTs));
                row.setTextColor(0xFF9AA0A6);
                row.setTextSize(12);
                row.setPadding(0, dp(4), 0, 0);
                row.setOnClickListener(v -> onStayTap(s));
                calDayDetail.addView(row);
            }
        }
        for (Store.Trip t : trips) {
            if (t.startTs < he && t.endTs > hs) {
                any = true;
                TextView row = new TextView(this);
                String mode = (t.mode != null && !t.mode.isEmpty()) ? t.mode : Store.modeFor(t.maxKmh);
                row.setText("移動　" + mode + "　" + tf.format(t.startTs) + " – " + tf.format(t.endTs)
                        + String.format(Locale.JAPAN, "　%.1f km", t.distM / 1000.0));
                row.setTextColor(0xFF9AA0A6);
                row.setTextSize(12);
                row.setPadding(0, dp(4), 0, 0);
                calDayDetail.addView(row);
            }
        }
        if (!any) {
            TextView none = new TextView(this);
            none.setText("この時間帯の記録はありません");
            none.setTextColor(0xFF9AA0A6);
            none.setTextSize(12);
            none.setPadding(0, dp(4), 0, 0);
            calDayDetail.addView(none);
        }
    }

    private void renderCalMonth() {
        Calendar first = (Calendar) calCursor.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        Calendar last = (Calendar) first.clone();
        last.add(Calendar.MONTH, 1);
        long fromMs = first.getTimeInMillis();
        long toMs = last.getTimeInMillis();
        Map<String, Store.DaySummary> sums = store.daySummaries(
                TrackingService.dayKey(fromMs), TrackingService.dayKey(toMs - 1));
        Map<String, Double> tripDist = tripDistByDay(fromMs, toMs);

        calPeriod.setText(new SimpleDateFormat("yyyy年M月", Locale.JAPAN).format(fromMs));

        int daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        double[] values = new double[daysInMonth + 1];
        double max = 0, total = 0;
        for (int d = 1; d <= daysInMonth; d++) {
            Calendar c = (Calendar) first.clone();
            c.set(Calendar.DAY_OF_MONTH, d);
            double v = calValueFor(TrackingService.dayKey(c.getTimeInMillis()), sums, tripDist);
            values[d] = v;
            if (v > max) max = v;
            if (v > 0) total += v;
        }
        calTotal.setText("合計 " + calFormat(total));

        calMonth.removeAllViews();
        LinearLayout header = new LinearLayout(this);
        String[] wd = {"日", "月", "火", "水", "木", "金", "土"};
        for (int i = 0; i < 7; i++) {
            TextView tv = new TextView(this);
            tv.setText(wd[i]);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(12);
            tv.setTextColor(i == 0 ? 0xFFE57373 : (i == 6 ? 0xFF8AB4F8 : 0xFF9AA0A6));
            header.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        calMonth.addView(header);

        long selectedDayStart = TrackingService.dayStart(System.currentTimeMillis())
                + dayOffset * 24L * 3600_000L;
        int startDow = first.get(Calendar.DAY_OF_WEEK) - 1;
        int cells = startDow + daysInMonth;
        int rows = (cells + 6) / 7;
        String todayKey = TrackingService.dayKey(System.currentTimeMillis());
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(3), 0, dp(3));
            for (int col = 0; col < 7; col++) {
                int idx = r * 7 + col;
                int day = idx - startDow + 1;
                FrameLayout cell = new FrameLayout(this);
                row.addView(cell, new LinearLayout.LayoutParams(0, dp(46), 1));
                if (day < 1 || day > daysInMonth) continue;
                Calendar c = (Calendar) first.clone();
                c.set(Calendar.DAY_OF_MONTH, day);
                long dayStart = c.getTimeInMillis();
                String key = TrackingService.dayKey(dayStart);
                double v = values[day];

                if (v > 0 || dayStart == selectedDayStart) {
                    int sizeDp = v > 0
                            ? (int) Math.round(14 + 26 * Math.sqrt(v / Math.max(max, 1)))
                            : 34;
                    View circle = new View(this);
                    GradientDrawable g = new GradientDrawable();
                    g.setShape(GradientDrawable.OVAL);
                    g.setColor(v > 0 ? METRIC_COLORS[calMetric] : 0x00000000);
                    if (dayStart == selectedDayStart) {
                        g.setStroke(dp(2), 0xFFFFFFFF);
                    } else if (key.equals(todayKey)) {
                        g.setStroke(dp(1), 0xFF9AA0A6);
                    }
                    circle.setBackground(g);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
                    lp.gravity = Gravity.CENTER;
                    cell.addView(circle, lp);
                }
                TextView num = new TextView(this);
                num.setText(String.valueOf(day));
                num.setGravity(Gravity.CENTER);
                num.setTextSize(13);
                // Any day with a circle gets dark digits — light text on the
                // pale metric colors was unreadable on small circles.
                num.setTextColor(v > 0 ? 0xFF101216 : (key.equals(todayKey) ? 0xFFF2F3F5 : 0xFF9AA0A6));
                if (key.equals(todayKey) || dayStart == selectedDayStart) {
                    num.setTypeface(Typeface.DEFAULT_BOLD);
                }
                cell.addView(num, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                cell.setOnClickListener(view -> selectDay(dayStart));
            }
            calMonth.addView(row);
        }

        buildCalWeeklyRows(first, sums, tripDist);
    }

    private void buildCalWeeklyRows(Calendar first, Map<String, Store.DaySummary> sums,
                                    Map<String, Double> tripDist) {
        calWeeklyRows.removeAllViews();
        int daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        Calendar c = (Calendar) first.clone();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.add(Calendar.DAY_OF_MONTH, -(c.get(Calendar.DAY_OF_WEEK) - 1));
        SimpleDateFormat f = new SimpleDateFormat("M月d日", Locale.JAPAN);
        while (true) {
            long weekStart = c.getTimeInMillis();
            double total = 0;
            boolean anyInMonth = false;
            for (int i = 0; i < 7; i++) {
                Calendar d = (Calendar) c.clone();
                d.add(Calendar.DAY_OF_MONTH, i);
                if (d.get(Calendar.MONTH) == first.get(Calendar.MONTH)) {
                    anyInMonth = true;
                    double v = calValueFor(TrackingService.dayKey(d.getTimeInMillis()), sums, tripDist);
                    if (v > 0) total += v;
                }
            }
            if (!anyInMonth && weekStart > first.getTimeInMillis()) break;
            if (anyInMonth) {
                Calendar end = (Calendar) c.clone();
                end.add(Calendar.DAY_OF_MONTH, 6);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(18), dp(12), dp(18), dp(12));
                row.setBackgroundResource(R.drawable.section_card);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                row.setLayoutParams(lp);
                TextView label = new TextView(this);
                label.setText(f.format(weekStart) + "〜" + f.format(end.getTimeInMillis()));
                label.setTextColor(0xFF9AA0A6);
                label.setTextSize(13);
                row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView val = new TextView(this);
                val.setText(calFormat(total));
                val.setTextColor(0xFFF2F3F5);
                val.setTextSize(14);
                val.setTypeface(Typeface.DEFAULT_BOLD);
                row.addView(val);
                calWeeklyRows.addView(row);
            }
            c.add(Calendar.DAY_OF_MONTH, 7);
            if (c.get(Calendar.MONTH) != first.get(Calendar.MONTH)
                    && c.getTimeInMillis() > first.getTimeInMillis()
                    && c.get(Calendar.DAY_OF_MONTH) > 7) break;
            if (weekStart > first.getTimeInMillis() && c.get(Calendar.DAY_OF_MONTH) > daysInMonth) break;
        }
    }

    private void renderCalWeek() {
        Calendar sun = (Calendar) calCursor.clone();
        sun.add(Calendar.DAY_OF_MONTH, -(sun.get(Calendar.DAY_OF_WEEK) - 1));
        long fromMs = sun.getTimeInMillis();
        Calendar endC = (Calendar) sun.clone();
        endC.add(Calendar.DAY_OF_MONTH, 7);
        long toMs = endC.getTimeInMillis();
        Map<String, Store.DaySummary> sums = store.daySummaries(
                TrackingService.dayKey(fromMs), TrackingService.dayKey(toMs - 1));
        Map<String, Double> tripDist = tripDistByDay(fromMs, toMs);

        SimpleDateFormat f = new SimpleDateFormat("M月d日", Locale.JAPAN);
        calPeriod.setText(f.format(fromMs) + "〜" + f.format(toMs - 1));

        double[] values = new double[7];
        double max = 0, total = 0;
        for (int i = 0; i < 7; i++) {
            Calendar d = (Calendar) sun.clone();
            d.add(Calendar.DAY_OF_MONTH, i);
            double v = calValueFor(TrackingService.dayKey(d.getTimeInMillis()), sums, tripDist);
            values[i] = v;
            if (v > max) max = v;
            if (v > 0) total += v;
        }
        calTotal.setText("合計 " + calFormat(total));

        calWeek.removeAllViews();
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setGravity(Gravity.BOTTOM);
        String[] wd = {"日", "月", "火", "水", "木", "金", "土"};
        String todayKey = TrackingService.dayKey(System.currentTimeMillis());
        for (int i = 0; i < 7; i++) {
            Calendar d = (Calendar) sun.clone();
            d.add(Calendar.DAY_OF_MONTH, i);
            long dayStart = d.getTimeInMillis();
            String key = TrackingService.dayKey(dayStart);
            double v = values[i];

            LinearLayout colLayout = new LinearLayout(this);
            colLayout.setOrientation(LinearLayout.VERTICAL);
            colLayout.setGravity(Gravity.CENTER_HORIZONTAL);
            cols.addView(colLayout, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView val = new TextView(this);
            val.setText(v > 0 ? calShort(v) : "—");
            val.setTextColor(0xFF9AA0A6);
            val.setTextSize(10);
            val.setGravity(Gravity.CENTER);
            colLayout.addView(val);

            FrameLayout barArea = new FrameLayout(this);
            barArea.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
            View bar = new View(this);
            int h = v > 0 ? Math.max(dp(4), (int) Math.round(dp(110) * (v / Math.max(max, 1)))) : 0;
            GradientDrawable g = new GradientDrawable();
            g.setColor(METRIC_COLORS[calMetric]);
            g.setCornerRadii(new float[]{dp(4), dp(4), dp(4), dp(4), 0, 0, 0, 0});
            bar.setBackground(g);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(26), h);
            blp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            barArea.addView(bar, blp);
            colLayout.addView(barArea);

            TextView lab = new TextView(this);
            lab.setText(wd[i] + " " + d.get(Calendar.DAY_OF_MONTH));
            lab.setTextColor(key.equals(todayKey) ? 0xFFF2F3F5 : 0xFF9AA0A6);
            if (key.equals(todayKey)) lab.setTypeface(Typeface.DEFAULT_BOLD);
            lab.setTextSize(11);
            lab.setGravity(Gravity.CENTER);
            lab.setPadding(0, dp(6), 0, 0);
            colLayout.addView(lab);

            colLayout.setOnClickListener(view -> selectDay(dayStart));
        }
        calWeek.addView(cols);
    }
}
