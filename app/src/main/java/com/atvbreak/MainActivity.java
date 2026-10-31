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
import android.content.res.ColorStateList;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置界面（TV 遥控器可操作：上下左右 + 确认键即可完成全部配置）。
 *
 * 配置同时支持：① 本界面直接改；② 改 /sdcard/Android/data/com.atvbreak/files/config.json 后导入。
 */
public class MainActivity extends Activity {

    private static final String TAG = "AtvBreak.Main";

    /** 进程内前台标志：应用在前台时不弹全屏 mask，改为状态卡橙色提示。 */
    private static volatile boolean inForeground;

    public static boolean isInForeground() {
        return inForeground;
    }

    private static final int TAB_OVERVIEW = 0;
    private static final int TAB_CONTINUOUS = 1;
    private static final int TAB_DAILY = 2;

    private int currentTab = TAB_OVERVIEW;

    private EditText etMessage;
    private EditText etThreshold;
    private EditText etDismiss;
    private EditText etResetOff;
    private CheckBox cbEnabled;
    private CheckBox cbSound;
    private CheckBox cbSoundAlarm;
    private TextView tvStatus;
    private TextView tvNext;
    private TimerRingView ringTimer;
    private View dotStatus;
    private View dotPermission;
    private View dotDaemon;
    private View dotBoot;
    private View dotBattery;
    private View dotDaily;
    private TextView tvPermission;
    private TextView tvDaemon;
    private TextView tvBoot;
    private TextView tvBattery;
    private TextView tvDailyRow;
    private TextView tvFilePath;

    // 每日累计
    private CheckBox cbDailyEnabled;
    private EditText etDailyThreshold;
    private EditText etDailyMask;
    private EditText etDailyRepeat;
    private TextView tvDailyTotal;
    private TextView tvDailySub;
    private ProgressBar barDaily;
    private View groupDailyConfig;
    private View noticeContinuousOff;
    private View noticeDailyMasterOff;
    private View noticeDailyOff;
    private final Button[] dailyConfigButtons = new Button[9];
    private final Button[] continuousConfigButtons = new Button[7];

    private View[] tabs;
    private View[] pages;

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
        tvNext = findViewById(R.id.tv_next);
        ringTimer = findViewById(R.id.ring_timer);
        dotStatus = findViewById(R.id.dot_status);
        dotPermission = findViewById(R.id.dot_permission);
        dotDaemon = findViewById(R.id.dot_daemon);
        dotBoot = findViewById(R.id.dot_boot);
        dotBattery = findViewById(R.id.dot_battery);
        tvPermission = findViewById(R.id.tv_permission);
        tvDaemon = findViewById(R.id.tv_daemon);
        tvBoot = findViewById(R.id.tv_boot);
        tvBattery = findViewById(R.id.tv_battery);
        tvDailyRow = findViewById(R.id.tv_daily_row);
        dotDaily = findViewById(R.id.dot_daily);
        tvFilePath = findViewById(R.id.tv_file_path);

        cbDailyEnabled = findViewById(R.id.cb_daily_enabled);
        etDailyThreshold = findViewById(R.id.et_daily_threshold);
        etDailyMask = findViewById(R.id.et_daily_mask);
        etDailyRepeat = findViewById(R.id.et_daily_repeat);
        tvDailyTotal = findViewById(R.id.tv_daily_total);
        tvDailySub = findViewById(R.id.tv_daily_sub);
        barDaily = findViewById(R.id.bar_daily);
        groupDailyConfig = findViewById(R.id.group_daily_config);
        noticeContinuousOff = findViewById(R.id.notice_continuous_off);
        noticeDailyMasterOff = findViewById(R.id.notice_daily_master_off);
        noticeDailyOff = findViewById(R.id.notice_daily_off);

        // tab 栏与三个页面（切换只改 visibility，不重建视图）
        tabs = new View[]{findViewById(R.id.tab_overview), findViewById(R.id.tab_continuous),
                findViewById(R.id.tab_daily)};
        pages = new View[]{findViewById(R.id.page_overview), findViewById(R.id.page_continuous),
                findViewById(R.id.page_daily)};
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            tabs[i].setOnClickListener(v -> switchTab(index));
            tabs[i].setOnFocusChangeListener(focusHighlightListener);
        }

        AppConfig cfg = AppConfig.fromPrefsOnly(this);
        etMessage.setText(cfg.message());
        etThreshold.setText(String.valueOf(cfg.thresholdMinutes()));
        etDismiss.setText(String.valueOf(cfg.dismissSeconds()));
        etResetOff.setText(String.valueOf(cfg.resetAfterScreenOffMinutes()));
        cbEnabled.setChecked(cfg.enabled());
        cbSound.setChecked(cfg.soundEnabled());
        cbSoundAlarm.setChecked(cfg.useAlarmStream());
        cbDailyEnabled.setChecked(cfg.dailyEnabled());
        etDailyThreshold.setText(String.valueOf(cfg.dailyThresholdMinutes()));
        etDailyMask.setText(String.valueOf(cfg.dailyMaskSeconds()));
        etDailyRepeat.setText(String.valueOf(cfg.dailyRepeatMinutes()));
        tvFilePath.setText(getString(R.string.config_file_path, AppConfig.configFile(this).getAbsolutePath()));

        // 主开关变化后立即刷新置灰态（不必等下一次每秒刷新）
        cbEnabled.setOnClickListener(v -> refreshEnabledStates());
        cbDailyEnabled.setOnClickListener(v -> refreshEnabledStates());

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

        // 「设为永不清零」：息屏清零时长填 0
        findViewById(R.id.chip_reset_never).setOnClickListener(v -> {
            etResetOff.setText("0");
            onSave(v);
        });

        // ---------- 每日累计 ----------
        bindDailyThresholdPreset(R.id.btn_daily_1h, 60);
        bindDailyThresholdPreset(R.id.btn_daily_2h, 120);
        bindDailyThresholdPreset(R.id.btn_daily_3h, 180);
        bindDailyMaskPreset(R.id.btn_daily_mask_30, 30);
        bindDailyMaskPreset(R.id.btn_daily_mask_60, 60);
        bindDailyMaskPreset(R.id.btn_daily_mask_90, 90);
        bindDailyRepeatPreset(R.id.btn_daily_repeat_none, 0);
        bindDailyRepeatPreset(R.id.btn_daily_repeat_30, 30);
        bindDailyRepeatPreset(R.id.btn_daily_repeat_60, 60);

        Button btnDailyTest = findViewById(R.id.btn_daily_test);
        btnDailyTest.setOnClickListener(v -> {
            AppConfig c = AppConfig.load(this);
            if (!ReminderOverlay.canDrawOverlays(this)) {
                toast(getString(R.string.toast_need_overlay_permission));
                return;
            }
            // 验收用：立即弹一次，不写已提醒标记、不改变累计
            DailyMaskOverlay.show(this, c, DailyWatchStats.todayTotalMs(this));
        });

        continuousConfigButtons[0] = findViewById(R.id.btn_preset_30);
        continuousConfigButtons[1] = findViewById(R.id.btn_preset_45);
        continuousConfigButtons[2] = findViewById(R.id.btn_preset_60);
        continuousConfigButtons[3] = findViewById(R.id.btn_dismiss_15);
        continuousConfigButtons[4] = findViewById(R.id.btn_dismiss_30);
        continuousConfigButtons[5] = findViewById(R.id.btn_dismiss_60);
        continuousConfigButtons[6] = findViewById(R.id.chip_reset_never);

        dailyConfigButtons[0] = findViewById(R.id.btn_daily_1h);
        dailyConfigButtons[1] = findViewById(R.id.btn_daily_2h);
        dailyConfigButtons[2] = findViewById(R.id.btn_daily_3h);
        dailyConfigButtons[3] = findViewById(R.id.btn_daily_mask_30);
        dailyConfigButtons[4] = findViewById(R.id.btn_daily_mask_60);
        dailyConfigButtons[5] = findViewById(R.id.btn_daily_mask_90);
        dailyConfigButtons[6] = findViewById(R.id.btn_daily_repeat_none);
        dailyConfigButtons[7] = findViewById(R.id.btn_daily_repeat_30);
        dailyConfigButtons[8] = findViewById(R.id.btn_daily_repeat_60);

        View[] focusables = new View[]{btnSave, btnReset, btnImport, btnExport, btnPermission,
                btnBattery, btnTest, btnDailyTest, cbEnabled, cbSound, cbSoundAlarm, cbDailyEnabled,
                etMessage, etThreshold, etDismiss, etResetOff,
                etDailyThreshold, etDailyMask, etDailyRepeat};
        for (View b : focusables) {
            b.setOnFocusChangeListener(focusHighlightListener);
        }
        for (View b : continuousConfigButtons) {
            b.setOnFocusChangeListener(focusHighlightListener);
        }
        for (View b : dailyConfigButtons) {
            b.setOnFocusChangeListener(focusHighlightListener);
        }

        switchTab(TAB_OVERVIEW);
        refreshEnabledStates();
        WatchTimerService.start(this);
    }

    private final View.OnFocusChangeListener focusHighlightListener = (v, hasFocus) -> {
        float scale = hasFocus ? 1.06f : 1.0f;
        v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        v.setSelected(hasFocus);
    };

    // ---------- tab 切换与置灰联动 ----------

    /**
     * 切换 tab：只改 visibility，不重建视图。
     *
     * GONE 的页面自动被 Android 焦点搜索跳过，遥控器不可能把焦点移进隐藏 tab；
     * EditText 中未保存的编辑也天然保留。
     */
    private void switchTab(int index) {
        if (index < 0 || index >= pages.length) {
            return;
        }
        currentTab = index;
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            tabs[i].setActivated(i == index);
        }
        refreshEnabledStates();
    }

    /**
     * 主开关跨 tab 的隐式依赖必须显式化：不置灰的话，用户在 tab 1 关了主开关，
     * 切到 tab 2 / tab 3 改完保存后毫无效果，而且看不到原因。
     */
    private void refreshEnabledStates() {
        boolean masterOn = cbEnabled.isChecked();
        boolean dailyOn = cbDailyEnabled.isChecked();

        // tab 2：主开关关闭时全部配置控件置灰
        setGroupEnabled(continuousConfigButtons, masterOn);
        etMessage.setEnabled(masterOn);
        etThreshold.setEnabled(masterOn);
        etDismiss.setEnabled(masterOn);
        etResetOff.setEnabled(masterOn);
        findViewById(R.id.btn_test).setEnabled(masterOn);
        findViewById(R.id.btn_reset).setEnabled(masterOn);
        noticeContinuousOff.setVisibility(masterOn ? View.GONE : View.VISIBLE);

        // tab 3：主开关关闭时整页配置置灰；每日开关关闭时四个配置项与测试按钮置灰
        cbDailyEnabled.setEnabled(masterOn);
        setGroupEnabled(dailyConfigButtons, masterOn && dailyOn);
        etDailyThreshold.setEnabled(masterOn && dailyOn);
        etDailyMask.setEnabled(masterOn && dailyOn);
        etDailyRepeat.setEnabled(masterOn && dailyOn);
        findViewById(R.id.btn_daily_test).setEnabled(masterOn && dailyOn);
        noticeDailyMasterOff.setVisibility(masterOn ? View.GONE : View.VISIBLE);
        noticeDailyOff.setVisibility(!masterOn || dailyOn ? View.GONE : View.VISIBLE);

        // 置灰时整组降透明度，视觉上明确「暂不生效」
        float alpha = masterOn ? 1f : 0.5f;
        findViewById(R.id.group_continuous).setAlpha(alpha);
        groupDailyConfig.setAlpha(masterOn && dailyOn ? 1f : 0.5f);
        cbDailyEnabled.setAlpha(masterOn ? 1f : 0.5f);

        updateChips();
    }

    private static void setGroupEnabled(View[] views, boolean enabled) {
        for (View v : views) {
            if (v != null) {
                v.setEnabled(enabled);
            }
        }
    }

    private void onSave(View v) {
        AppConfig cfg = AppConfig.fromPrefsOnly(this);
        cfg.message = etMessage.getText().toString().trim().isEmpty()
                ? AppConfig.DEFAULT_MESSAGE : etMessage.getText().toString().trim();
        cfg.thresholdMinutes = intOf(etThreshold, AppConfig.DEFAULT_THRESHOLD_MINUTES);
        cfg.dismissSeconds = intOf(etDismiss, AppConfig.DEFAULT_DISMISS_SECONDS);
        cfg.resetAfterScreenOffMinutes = intOfAllowZero(etResetOff,
                AppConfig.DEFAULT_RESET_AFTER_SCREEN_OFF_MINUTES);
        cfg.enabled = cbEnabled.isChecked();
        cfg.soundEnabled = cbSound.isChecked();
        cfg.soundStream = cbSoundAlarm.isChecked()
                ? AppConfig.SOUND_STREAM_ALARM : AppConfig.SOUND_STREAM_NOTIFICATION;
        // 每日累计：越界一律钳制到范围内（与配置文件解析同口径）
        cfg.dailyEnabled = cbDailyEnabled.isChecked();
        cfg.dailyThresholdMinutes = AppConfig.clamp(intOf(etDailyThreshold,
                        AppConfig.DEFAULT_DAILY_THRESHOLD_MINUTES),
                AppConfig.DAILY_THRESHOLD_MIN, AppConfig.DAILY_THRESHOLD_MAX);
        cfg.dailyMaskSeconds = AppConfig.clamp(intOf(etDailyMask,
                        AppConfig.DEFAULT_DAILY_MASK_SECONDS),
                AppConfig.DAILY_MASK_SECONDS_MIN, AppConfig.DAILY_MASK_SECONDS_MAX);
        cfg.dailyRepeatMinutes = AppConfig.clamp(intOfAllowZero(etDailyRepeat,
                        AppConfig.DEFAULT_DAILY_REPEAT_MINUTES),
                AppConfig.DAILY_REPEAT_MINUTES_MIN, AppConfig.DAILY_REPEAT_MINUTES_MAX);
        cfg.save(this);
        AppConfig.exportToFile(this, cfg);
        WatchTimerService.send(this, WatchTimerService.ACTION_RELOAD);
        toast(getString(R.string.toast_saved));
        // 回填钳制后的值，让用户看到真正生效的数字
        etDailyThreshold.setText(String.valueOf(cfg.dailyThresholdMinutes()));
        etDailyMask.setText(String.valueOf(cfg.dailyMaskSeconds()));
        etDailyRepeat.setText(String.valueOf(cfg.dailyRepeatMinutes()));
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

    private void bindDailyThresholdPreset(int id, int minutes) {
        Button b = findViewById(id);
        b.setOnClickListener(v -> {
            etDailyThreshold.setText(String.valueOf(minutes));
            onSave(v);
        });
    }

    private void bindDailyMaskPreset(int id, int seconds) {
        Button b = findViewById(id);
        b.setOnClickListener(v -> {
            etDailyMask.setText(String.valueOf(seconds));
            onSave(v);
        });
    }

    private void bindDailyRepeatPreset(int id, int minutes) {
        Button b = findViewById(id);
        b.setOnClickListener(v -> {
            etDailyRepeat.setText(String.valueOf(minutes));
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

    /** 与 {@link #intOf} 相同，但允许 0（息屏清零时长填 0 表示永不清零）。 */
    private static int intOfAllowZero(EditText et, int def) {
        try {
            int v = Integer.parseInt(et.getText().toString().trim());
            return v >= 0 ? v : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 让当前生效的数值对应的预设胶囊高亮。 */
    private void updateChips() {
        int threshold = intOf(etThreshold, 0);
        setActivated(R.id.btn_preset_30, threshold == 30);
        setActivated(R.id.btn_preset_45, threshold == 45);
        setActivated(R.id.btn_preset_60, threshold == 60);

        int dismiss = intOf(etDismiss, 0);
        setActivated(R.id.btn_dismiss_15, dismiss == 15);
        setActivated(R.id.btn_dismiss_30, dismiss == 30);
        setActivated(R.id.btn_dismiss_60, dismiss == 60);

        setActivated(R.id.chip_reset_never, intOfAllowZero(etResetOff, -1) == 0);

        int dailyThreshold = intOf(etDailyThreshold, 0);
        setActivated(R.id.btn_daily_1h, dailyThreshold == 60);
        setActivated(R.id.btn_daily_2h, dailyThreshold == 120);
        setActivated(R.id.btn_daily_3h, dailyThreshold == 180);

        int maskSeconds = intOf(etDailyMask, 0);
        setActivated(R.id.btn_daily_mask_30, maskSeconds == 30);
        setActivated(R.id.btn_daily_mask_60, maskSeconds == 60);
        setActivated(R.id.btn_daily_mask_90, maskSeconds == 90);

        int repeat = intOfAllowZero(etDailyRepeat, -1);
        setActivated(R.id.btn_daily_repeat_none, repeat == 0);
        setActivated(R.id.btn_daily_repeat_30, repeat == 30);
        setActivated(R.id.btn_daily_repeat_60, repeat == 60);
    }

    private void setActivated(int id, boolean on) {
        View v = findViewById(id);
        if (v != null) {
            v.setActivated(on);
        }
    }

    private void tintDot(View dot, int color) {
        if (dot != null) {
            dot.setBackgroundTintList(ColorStateList.valueOf(color));
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
        cbDailyEnabled.setChecked(cfg.dailyEnabled());
        etDailyThreshold.setText(String.valueOf(cfg.dailyThresholdMinutes()));
        etDailyMask.setText(String.valueOf(cfg.dailyMaskSeconds()));
        etDailyRepeat.setText(String.valueOf(cfg.dailyRepeatMinutes()));
        refreshEnabledStates();
    }

    private void refreshStatus() {
        AppConfig cfg = AppConfig.load(this);
        long watched = WatchTimerService.currentWatchingMs(this);
        long remain = Math.max(0, cfg.thresholdMillis() - watched);
        int warn = getResources().getColor(R.color.warn);
        int ok = getResources().getColor(R.color.ok);
        int neutral = getResources().getColor(R.color.text_secondary);

        // 顶栏状态胶囊 + 环形进度
        tvStatus.setText(getString(R.string.pill_status_format,
                cfg.enabled() ? getString(R.string.status_on) : getString(R.string.status_off)));
        tintDot(dotStatus, cfg.enabled() ? ok : warn);
        ringTimer.setProgress(watched, cfg.thresholdMillis());
        tvNext.setText(cfg.enabled()
                ? getString(R.string.next_remind_format, remain / 60000, (remain / 1000) % 60)
                : getString(R.string.next_remind_off));
        updateChips();
        refreshDailyStatus(cfg);

        boolean granted = ReminderOverlay.canDrawOverlays(this);
        tvPermission.setText(granted ? getString(R.string.permission_granted)
                : getString(R.string.permission_missing));
        tvPermission.setTextColor(granted ? ok : warn);
        tintDot(dotPermission, granted ? ok : warn);
        findViewById(R.id.btn_permission).setVisibility(granted ? View.GONE : View.VISIBLE);

        // 后台守护状态：心跳超过 2 个看门狗周期认为已停止（看门狗会自动拉起）
        long age = WatchTimerService.heartbeatAgeMs(this);
        boolean daemonOk;
        if (age < 0) {
            tvDaemon.setText(getString(R.string.daemon_never));
            tvDaemon.setTextColor(warn);
            daemonOk = false;
        } else if (age > WatchTimerService.TICK_INTERVAL_MS * 2L) {
            tvDaemon.setText(getString(R.string.daemon_stopped));
            tvDaemon.setTextColor(warn);
            daemonOk = false;
        } else if (age < 60_000L) {
            tvDaemon.setText(getString(R.string.daemon_running_just));
            tvDaemon.setTextColor(ok);
            daemonOk = true;
        } else {
            tvDaemon.setText(getString(R.string.daemon_running, (age / 60000L) + " 分钟"));
            tvDaemon.setTextColor(ok);
            daemonOk = true;
        }
        tintDot(dotDaemon, daemonOk ? ok : warn);

        String bootWall = WatchTimerService.bootWallClock(this);
        long bootAt = WatchTimerService.bootAtElapsedMs(this);
        if (bootWall == null || bootAt < 0) {
            tvBoot.setText(getString(R.string.boot_never));
            tvBoot.setTextColor(neutral);
            tintDot(dotBoot, neutral);
        } else {
            tvBoot.setText(getString(R.string.boot_ok, bootWall, bootAt / 60000L, (bootAt / 1000L) % 60L));
            tvBoot.setTextColor(ok);
            tintDot(dotBoot, ok);
        }

        boolean ignoring = isIgnoringBatteryOptimization();
        tvBattery.setText(ignoring ? getString(R.string.battery_ok) : getString(R.string.battery_missing));
        tvBattery.setTextColor(ignoring ? ok : warn);
        tintDot(dotBattery, ignoring ? ok : warn);
        findViewById(R.id.btn_battery).setVisibility(ignoring ? View.GONE : View.VISIBLE);
    }

    /**
     * 每日累计：tab 1 紧凑行与 tab 3 大数字同源（{@link DailyWatchStats#todayTotalMs}），
     * 每秒刷新一次。同一时刻只有一个 tab 可见，不存在同屏两个刷新数字。
     */
    private void refreshDailyStatus(AppConfig cfg) {
        long today = DailyWatchStats.todayTotalMs(this);
        long limit = cfg.dailyThresholdMillis();
        boolean over = today >= limit;
        int warn = getResources().getColor(R.color.warn);
        int ok = getResources().getColor(R.color.ok);

        // tab 1 紧凑行
        String rowText;
        if (!cfg.dailyActive()) {
            rowText = getString(R.string.status_off);
            tintDot(dotDaily, getResources().getColor(R.color.text_secondary));
        } else if (!over) {
            rowText = getString(R.string.daily_status_compact,
                    DurationFormat.formatDuration(today), DurationFormat.formatDuration(limit));
            tintDot(dotDaily, ok);
        } else if (isInForeground()) {
            rowText = getString(R.string.daily_status_over_foreground);
            tintDot(dotDaily, warn);
        } else {
            rowText = getString(R.string.daily_status_over, DurationFormat.formatDuration(today));
            tintDot(dotDaily, warn);
        }
        tvDailyRow.setText(rowText);
        tvDailyRow.setTextColor(over && cfg.dailyActive() ? warn : getResources().getColor(R.color.text_secondary));

        // tab 3 大数字 + 进度条 + 昨日
        tvDailyTotal.setText(DurationFormat.formatDuration(today));
        tvDailyTotal.setTextColor(over ? warn : getResources().getColor(R.color.text_primary));
        int progress = limit <= 0 ? 1000 : (int) Math.min(1000L, today * 1000L / limit);
        barDaily.setProgress(progress);

        long yesterday = DailyWatchStats.yesterdayMs(this);
        String yesterdayText = yesterday < 0L
                ? getString(R.string.label_daily_yesterday_none)
                : DurationFormat.formatDuration(yesterday);
        tvDailySub.setText(getString(R.string.label_daily_limit,
                DurationFormat.formatDuration(limit), yesterdayText));
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
        inForeground = true;
        // 用户正在操作设置页时不希望被全屏遮住
        DailyMaskOverlay.dismiss(this);
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
        inForeground = false;
        uiHandler.removeCallbacks(statusTask);
        super.onPause();
        // 退出应用后若已超限且今天还没弹过，立刻补一次（不等下一个闹钟）
        WatchTimerService.scheduleDaily(this);
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
