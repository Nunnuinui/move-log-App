package app.musemovelog;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;

/** Recording toggle, permissions and data import — rarely touched settings. */
public class SettingsActivity extends Activity {

    private static final int REQ_LOCATION = 1001;
    private static final int REQ_ACTIVITY = 1002;
    private static final int REQ_HEALTH = 1005;
    private static final int REQ_HEALTH_MODULE = 1007;
    private static final int REQ_TAKEOUT = 1009;

    private Store store;
    private ToggleView trackingToggle;
    private TextView recordingState;
    private TextView permLocation, permBackground, permActivity, permBattery, hcStatus;
    private Button btnImport, btnHcSettings, btnTakeout;
    private boolean updatingToggle;
    private boolean pendingEnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        View scroll = findViewById(R.id.settings_scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars =
                    insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        store = new Store(this);

        trackingToggle = findViewById(R.id.tracking_toggle);
        recordingState = findViewById(R.id.recording_state);
        permLocation = findViewById(R.id.perm_location);
        permBackground = findViewById(R.id.perm_background);
        permActivity = findViewById(R.id.perm_activity);
        permBattery = findViewById(R.id.perm_battery);
        hcStatus = findViewById(R.id.hc_status);
        btnImport = findViewById(R.id.btn_import);
        btnHcSettings = findViewById(R.id.btn_hc_settings);
        btnTakeout = findViewById(R.id.btn_takeout);

        trackingToggle.setOnCheckedChangeListener((view, checked) -> {
            if (updatingToggle) return;
            if (checked) {
                if (!hasFineLocation()) {
                    updatingToggle = true;
                    trackingToggle.setChecked(false);
                    updatingToggle = false;
                    pendingEnable = true;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                    Toast.makeText(this, "記録には位置情報の許可が必要です", Toast.LENGTH_SHORT).show();
                    return;
                }
                TrackingService.setEnabled(this, true);
                TrackingService.start(this);
            } else {
                TrackingService.setEnabled(this, false);
                TrackingService.stop(this);
            }
            refresh();
        });

        findViewById(R.id.btn_location).setOnClickListener(v ->
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION));
        findViewById(R.id.btn_background).setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Toast.makeText(this, "「権限」→「位置情報」→「常に許可」を選んでください",
                    Toast.LENGTH_LONG).show();
        });
        findViewById(R.id.btn_activity).setOnClickListener(v ->
                requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, REQ_ACTIVITY));
        findViewById(R.id.btn_battery).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        });

        btnImport.setOnClickListener(v -> {
            Dbg.log(this, "import tap; sdkStatus=" + HealthImport.sdkStatus(this));
            if (!HealthImport.isAvailable(this)) {
                Toast.makeText(this, "この端末ではヘルスコネクトが使えません", Toast.LENGTH_SHORT).show();
                return;
            }
            HealthImport.grantedNow(this, granted -> {
                Dbg.log(this, "granted before: " + granted);
                boolean ok = granted.containsAll(java.util.Set.of(
                        "android.permission.health.READ_STEPS",
                        "android.permission.health.READ_DISTANCE"));
                if (ok) {
                    doImport();
                } else {
                    try {
                        Intent intent = HealthImport.createPermissionIntent(this);
                        startActivityForResult(intent, REQ_HEALTH);
                    } catch (Exception e) {
                        Dbg.log(this, "contract intent failed: " + e);
                        try {
                            startActivityForResult(HealthImport.createRuntimePermissionIntent(),
                                    REQ_HEALTH_MODULE);
                        } catch (Exception e2) {
                            Dbg.log(this, "manual intent failed: " + e2);
                            Toast.makeText(this, "ヘルスコネクトを開けませんでした", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                return kotlin.Unit.INSTANCE;
            });
        });
        btnHcSettings.setOnClickListener(v -> {
            try {
                startActivity(HealthImport.manageDataIntent(this));
                Toast.makeText(this, "「アプリの権限」で Move Log を選び、歩数と距離を許可してください",
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Dbg.log(this, "manage data intent failed: " + e);
                Toast.makeText(this, "ヘルスコネクトの設定を開けませんでした", Toast.LENGTH_SHORT).show();
            }
        });
        btnTakeout.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(pick, REQ_TAKEOUT);
            } catch (Exception e) {
                Toast.makeText(this, "ファイル選択を開けませんでした", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // If permission was granted in Health Connect settings, import once.
        if (HealthImport.isAvailable(this)
                && store.getKv("hc_imported_days", "").isEmpty()) {
            HealthImport.grantedNow(this, granted -> {
                boolean ok = granted.containsAll(java.util.Set.of(
                        "android.permission.health.READ_STEPS",
                        "android.permission.health.READ_DISTANCE"));
                if (ok && store.getKv("hc_imported_days", "").isEmpty()) {
                    Dbg.log(this, "auto import on resume (permission present)");
                    doImport();
                }
                return kotlin.Unit.INSTANCE;
            });
        }
    }

    private void doImport() {
        btnImport.setEnabled(false);
        btnImport.setText("取り込み中…");
        HealthImport.importHistory(this, store, 1825, (count, error) -> {
            btnImport.setEnabled(true);
            btnImport.setText("ヘルスコネクトから取り込む");
            if (error == null && count >= 0) {
                store.setKv("hc_imported_days", String.valueOf(count));
                store.setKv("hc_imported_at", String.valueOf(System.currentTimeMillis()));
                Toast.makeText(this, "過去 " + count + " 日分を取り込みました", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "取り込めませんでした", Toast.LENGTH_SHORT).show();
            }
            refresh();
            return kotlin.Unit.INSTANCE;
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Dbg.log(this, "settings onActivityResult req=" + requestCode + " result=" + resultCode);
        if (requestCode == REQ_HEALTH || requestCode == REQ_HEALTH_MODULE) {
            HealthImport.grantedNow(this, granted -> {
                Dbg.log(this, "granted after: " + granted);
                boolean ok = granted.containsAll(java.util.Set.of(
                        "android.permission.health.READ_STEPS",
                        "android.permission.health.READ_DISTANCE"));
                if (ok) {
                    doImport();
                } else {
                    Toast.makeText(this, "ヘルスコネクトの許可が必要です", Toast.LENGTH_SHORT).show();
                }
                return kotlin.Unit.INSTANCE;
            });
        }
        if (requestCode == REQ_TAKEOUT && resultCode == RESULT_OK && data != null) {
            java.util.List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) {
                Toast.makeText(this, "Takeoutデータを読み込んでいます…", Toast.LENGTH_SHORT).show();
                TakeoutImport.importUris(this, uris, store, (days, error) -> {
                    if (error != null) {
                        Toast.makeText(this, "読み込みに失敗しました", Toast.LENGTH_LONG).show();
                    } else if (days > 0) {
                        Toast.makeText(this,
                                "Takeoutから " + days + " 日分を取り込みました",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this,
                                "取り込めるデータが見つかりませんでした（Fitの「Daily activity metrics」CSVを含むZIPを選んでください）",
                                Toast.LENGTH_LONG).show();
                    }
                    refresh();
                });
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && hasFineLocation()) {
            if (pendingEnable) {
                pendingEnable = false;
                TrackingService.setEnabled(this, true);
                TrackingService.start(this);
            } else if (TrackingService.isEnabled(this)) {
                TrackingService.start(this);
            }
        }
        refresh();
    }

    private boolean hasFineLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refresh() {
        boolean enabled = TrackingService.isEnabled(this);
        if (trackingToggle.isChecked() != enabled) {
            updatingToggle = true;
            trackingToggle.setChecked(enabled);
            updatingToggle = false;
        }
        recordingState.setText(enabled ? "記録中" : "停止中");

        permLocation.setText(hasFineLocation() ? "許可済み" : "未許可");
        boolean bg = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        permBackground.setText(bg ? "常に許可になっています" : "「常に許可」が必要です");
        boolean act = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED;
        permActivity.setText(act ? "許可済み" : "歩数の取得に使います");
        PowerManager pm = getSystemService(PowerManager.class);
        boolean batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        permBattery.setText(batteryOk ? "制限なしになっています" : "制限なし推奨");

        String importedDays = store.getKv("hc_imported_days", "");
        if (!HealthImport.isAvailable(this)) {
            hcStatus.setText("この端末ではヘルスコネクトが使えません。");
            btnImport.setEnabled(false);
        } else if (!importedDays.isEmpty()) {
            long at = 0;
            try {
                at = Long.parseLong(store.getKv("hc_imported_at", "0"));
            } catch (NumberFormatException ignored) {
            }
            String when = at > 0 ? new SimpleDateFormat("M月d日 HH:mm", Locale.JAPAN).format(at) : "";
            hcStatus.setText("取り込み済み：過去 " + importedDays + " 日分の歩数・距離"
                    + (when.isEmpty() ? "" : "（" + when + "）")
                    + "。新しい分はまた取り込めます。");
            btnImport.setEnabled(true);
        } else {
            hcStatus.setText("ヘルスコネクトにある過去の歩数・距離を取り込めます（Samsung Healthなどで記録した分も、ヘルスコネクトに連携されていれば対象です）。");
            btnImport.setEnabled(true);
        }
    }
}
