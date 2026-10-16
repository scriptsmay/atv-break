/*
 * atv-break - Android TV 定时休息提醒
 * Copyright (C) 2026 scriptsmay
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.atvbreak;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置界面（TV 遥控器可操作：上下左右 + 确认键即可完成全部配置）。
 *
 * 配置同时支持：① 本界面直接改；② 改 /sdcard/Android/data/com.atvbreak/files/config.json 后导入。
 */
public class MainActivity extends Activity {

    private static final String TAG = "AtvBreak.Main";

    private EditText etMessage;
    private EditText etThreshold;
    private EditText etDismiss;
    private EditText etResetOff;
    private CheckBox cbEnabled;
    private CheckBox cbSound;
    private CheckBox cbSoundAlarm;
    private TextView tvStatus;
    private TextView tvPermission;
    private TextView tvDaemon;
    private TextView tvBoot;
    private TextView tvBattery;
    private TextView tvFilePath;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusTask = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            uiHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etMessage = findViewById(R.id.et_message);
        etThreshold = findViewById(R.id.et_threshold);
        etDismiss = findViewById(R.id.et_dismiss);
        etResetOff = findViewById(R.id.et_reset_off);
        cbEnabled = findViewById(R.id.cb_enabled);
        cbSound = findViewById(R.id.cb_sound);
        cbSoundAlarm = findViewById(R.id.cb_sound_alarm);
        tvStatus = findViewById(R.id.tv_status);
        tvPermission = findViewById(R.id.tv_permission);
        tvDaemon = findViewById(R.id.tv_daemon);
        tvBoot = findViewById(R.id.tv_boot);
        tvBattery = findViewById(R.id.tv_battery);
        tvFilePath = findViewById(R.id.tv_file_path);

        AppConfig cfg = AppConfig.fromPrefsOnly(this);
        etMessage.setText(cfg.message());
        etThreshold.setText(String.valueOf(cfg.thresholdMinutes()));
        etDismiss.setText(String.valueOf(cfg.dismissSeconds()));
        etResetOff.setText(String.valueOf(cfg.resetAfterScreenOffMinutes()));
        cbEnabled.setChecked(cfg.enabled());
        cbSound.setChecked(cfg.soundEnabled());
        cbSoundAlarm.setChecked(cfg.useAlarmStream());
        tvFilePath.setText(getString(R.string.config_file_path, AppConfig.configFile(this).getAbsolutePath()));

        Button btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(this::onSave);

        Button btnTest = findViewById(R.id.btn_test);
        btnTest.setOnClickListener(v -> {
            AppConfig c = AppConfig.load(this);
            if (!ReminderOverlay.canDrawOverlays(this)) {
                toast(getString(R.string.toast_need_overlay_permission));
                return;
            }
            ReminderOverlay.show(this, c);
        });
        btnTest.setOnFocusChangeListener(focusHighlightListener);

        Button btnReset = findViewById(R.id.btn_reset);
        btnReset.setOnClickListener(v -> {
            WatchTimerService.send(this, WatchTimerService.ACTION_RESET);
            toast(getString(R.string.toast_timer_reset));
            refreshStatus();
        });

        Button btnImport = findViewById(R.id.btn_import);
        btnImport.setOnClickListener(v -> {
            AppConfig imported = AppConfig.importFromFile(this);
            if (imported == null) {
                toast(getString(R.string.toast_import_failed));
                return;
            }
            fill(imported);
            WatchTimerService.send(this, WatchTimerService.ACTION_RELOAD);
            toast(getString(R.string.toast_import_done));
        });

        Button btnExport = findViewById(R.id.btn_export);
        btnExport.setOnClickListener(v -> {
            AppConfig c = AppConfig.load(this);
            java.io.File f = AppConfig.exportToFile(this, c);
            toast(f == null ? getString(R.string.toast_export_failed)
                    : getString(R.string.toast_export_done, f.getAbsolutePath()));
        });

        Button btnPermission = findViewById(R.id.btn_permission);
        btnPermission.setOnClickListener(v -> requestOverlayPermission());

        Button btnBattery = findViewById(R.id.btn_battery);
        btnBattery.setOnClickListener(v -> requestIgnoreBatteryOptimization());

        bindPreset(R.id.btn_preset_30, 30);
        bindPreset(R.id.btn_preset_45, 45);
        bindPreset(R.id.btn_preset_60, 60);
        bindDismissPreset(R.id.btn_dismiss_15, 15);
        bindDismissPreset(R.id.btn_dismiss_30, 30);
        bindDismissPreset(R.id.btn_dismiss_60, 60);

        for (View b : new View[]{btnSave, btnReset, btnImport, btnExport, btnPermission, btnBattery,
                findViewById(R.id.btn_preset_30), findViewById(R.id.btn_preset_45),
                findViewById(R.id.btn_preset_60), findViewById(R.id.btn_dismiss_15),
                findViewById(R.id.btn_dismiss_30), findViewById(R.id.btn_dismiss_60),
                cbSound, cbSoundAlarm}) {
            b.setOnFocusChangeListener(focusHighlightListener);
        }

        WatchTimerService.start(this);
    }

    private final View.OnFocusChangeListener focusHighlightListener = (v, hasFocus) -> {
        float scale = hasFocus ? 1.06f : 1.0f;
        v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        v.setSelected(hasFocus);
    };

    private void onSave(View v) {
        AppConfig cfg = AppConfig.fromPrefsOnly(this);
        cfg.message = etMessage.getText().toString().trim().isEmpty()
                ? AppConfig.DEFAULT_MESSAGE : etMessage.getText().toString().trim();
        cfg.thresholdMinutes = intOf(etThreshold, AppConfig.DEFAULT_THRESHOLD_MINUTES);
        cfg.dismissSeconds = intOf(etDismiss, AppConfig.DEFAULT_DISMISS_SECONDS);
        cfg.resetAfterScreenOffMinutes = intOf(etResetOff, AppConfig.DEFAULT_RESET_AFTER_SCREEN_OFF_MINUTES);
        cfg.enabled = cbEnabled.isChecked();
        cfg.soundEnabled = cbSound.isChecked();
        cfg.soundStream = cbSoundAlarm.isChecked()
                ? AppConfig.SOUND_STREAM_ALARM : AppConfig.SOUND_STREAM_NOTIFICATION;
        cfg.save(this);
        AppConfig.exportToFile(this, cfg);
        WatchTimerService.send(this, WatchTimerService.ACTION_RELOAD);
        toast(getString(R.string.toast_saved));
        refreshStatus();
    }

    private void bindPreset(int id, int minutes) {
        Button b = findViewById(id);
        b.setOnClickListener(v -> {
            etThreshold.setText(String.valueOf(minutes));
            onSave(v);
        });
    }

    /** 浮层自动关闭秒数的快捷选值（15 秒 / 30 秒 / 1 分钟）。 */
    private void bindDismissPreset(int id, int seconds) {
        Button b = findViewById(id);
        b.setOnClickListener(v -> {
            etDismiss.setText(String.valueOf(seconds));
            onSave(v);
        });
    }

    private static int intOf(EditText et, int def) {
        try {
            int v = Integer.parseInt(et.getText().toString().trim());
            return v > 0 ? v : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void fill(AppConfig cfg) {
        etMessage.setText(cfg.message());
        etThreshold.setText(String.valueOf(cfg.thresholdMinutes()));
        etDismiss.setText(String.valueOf(cfg.dismissSeconds()));
        etResetOff.setText(String.valueOf(cfg.resetAfterScreenOffMinutes()));
        cbEnabled.setChecked(cfg.enabled());
        cbSound.setChecked(cfg.soundEnabled());
        cbSoundAlarm.setChecked(cfg.useAlarmStream());
    }

    private void refreshStatus() {
        AppConfig cfg = AppConfig.load(this);
        long watched = WatchTimerService.currentWatchingMs(this);
        long remain = Math.max(0, cfg.thresholdMillis() - watched);
        tvStatus.setText(getString(R.string.status_format,
                watched / 60000, remain / 60000, (remain / 1000) % 60,
                cfg.enabled() ? getString(R.string.status_on) : getString(R.string.status_off)));

        boolean granted = ReminderOverlay.canDrawOverlays(this);
        tvPermission.setText(granted ? getString(R.string.permission_granted)
                : getString(R.string.permission_missing));
        tvPermission.setTextColor(getResources().getColor(
                granted ? R.color.ok : R.color.warn));
        findViewById(R.id.btn_permission).setVisibility(granted ? View.GONE : View.VISIBLE);

        // 后台守护状态：心跳超过 2 个看门狗周期认为已停止（看门狗会自动拉起）
        long age = WatchTimerService.heartbeatAgeMs(this);
        int warn = getResources().getColor(R.color.warn);
        int ok = getResources().getColor(R.color.ok);
        if (age < 0) {
            tvDaemon.setText(getString(R.string.daemon_never));
            tvDaemon.setTextColor(warn);
        } else if (age > WatchTimerService.TICK_INTERVAL_MS * 2L) {
            tvDaemon.setText(getString(R.string.daemon_stopped));
            tvDaemon.setTextColor(warn);
        } else if (age < 60_000L) {
            tvDaemon.setText(getString(R.string.daemon_running_just));
            tvDaemon.setTextColor(ok);
        } else {
            tvDaemon.setText(getString(R.string.daemon_running, (age / 60000L) + " 分钟"));
            tvDaemon.setTextColor(ok);
        }

        String bootWall = WatchTimerService.bootWallClock(this);
        long bootAt = WatchTimerService.bootAtElapsedMs(this);
        if (bootWall == null || bootAt < 0) {
            tvBoot.setText(getString(R.string.boot_never));
            tvBoot.setTextColor(getResources().getColor(R.color.text_secondary));
        } else {
            tvBoot.setText(getString(R.string.boot_ok, bootWall, bootAt / 60000L, (bootAt / 1000L) % 60L));
            tvBoot.setTextColor(ok);
        }

        boolean ignoring = isIgnoringBatteryOptimization();
        tvBattery.setText(ignoring ? getString(R.string.battery_ok) : getString(R.string.battery_missing));
        tvBattery.setTextColor(ignoring ? ok : warn);
        findViewById(R.id.btn_battery).setVisibility(ignoring ? View.GONE : View.VISIBLE);
    }

    private boolean isIgnoringBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimization()) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception e) {
                Log.w(TAG, "无法打开电池优化授权页", e);
            }
        }
        toast(getString(R.string.toast_battery_manual, getPackageName()));
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception e) {
                Log.w(TAG, "无法打开悬浮窗授权页", e);
            }
        }
        toast(getString(R.string.toast_manual_permission, getPackageName()));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppConfig cfg = AppConfig.load(this);
        if (!etMessage.isFocused()) {
            fill(cfg);
        }
        refreshStatus();
        uiHandler.removeCallbacks(statusTask);
        uiHandler.post(statusTask);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(statusTask);
        super.onPause();
    }

    /** 让 EditText 在遥控器下可编辑（不弹出软键盘、不影响方向键导航）。 */
    static void configureNumericInput(EditText et) {
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setSingleLine(true);
        et.setSelectAllOnFocus(true);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && findViewById(R.id.btn_save) != null) {
            View root = ((ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
            if (root != null) {
                root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        }
    }
}
