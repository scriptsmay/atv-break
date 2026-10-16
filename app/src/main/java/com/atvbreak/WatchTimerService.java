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

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * 连续观看时长计时服务。
 *
 * 设计要点（低开销 + 抗清理）：
 * 1. 亮屏 / 息屏走动态广播，事件驱动，进程内没有任何轮询线程或定时循环；
 * 2. 到点提醒交给系统 AlarmManager。闹钟由系统持有，进程被杀也照样触发；
 *    <b>闹钟一旦设定就不再取消</b>（息屏期间改用不唤醒设备的闹钟，亮屏后系统补投），
 *    因此不存在「进程死了就再也没人重新设闹钟」的失效路径；
 * 3. 每 15 分钟一次看门狗（ACTION_TICK）：对账屏幕状态、补设闹钟、必要时拉起本服务。
 *    亮屏期间才有唤醒能力，息屏期间由系统在下次唤醒时补投，不额外耗电；
 * 4. 计时状态写 SharedPreferences，进程重启 / 系统重启后按当前屏幕状态继续累计。
 *
 * 计入口径：只累计「亮屏」时长；息屏超过 resetAfterScreenOffMinutes 视为已休息，计时清零。
 */
public class WatchTimerService extends Service {

    private static final String TAG = "AtvBreak.Service";

    public static final String ACTION_START = "com.atvbreak.action.START";
    public static final String ACTION_REMIND = "com.atvbreak.action.REMIND";
    public static final String ACTION_TICK = "com.atvbreak.action.TICK";
    public static final String ACTION_TEST = "com.atvbreak.action.TEST_REMINDER";
    public static final String ACTION_RESET = "com.atvbreak.action.RESET_TIMER";
    public static final String ACTION_RELOAD = "com.atvbreak.action.RELOAD_CONFIG";

    private static final String CHANNEL_ID = "atv_break_timer";
    private static final int NOTIF_ID = 1;
    private static final int REQ_REMIND = 1001;
    private static final int REQ_TICK = 1003;

    /** 看门狗周期：15 分钟。开销接近零，只做一次状态对账。 */
    static final long TICK_INTERVAL_MS = 15L * 60L * 1000L;

    private static final String ST_ACC = "st_accumulated_ms";
    private static final String ST_ON_SINCE = "st_screen_on_since";
    private static final String ST_OFF_MARK = "st_screen_off_mark";
    /** 服务心跳（elapsedRealtime）。看门狗据此判断服务是否还活着。 */
    private static final String ST_HEARTBEAT = "st_heartbeat";
    /** 上次收到开机广播的时刻（elapsedRealtime / 墙上时间）。 */
    private static final String ST_BOOT_AT = "st_boot_at";
    private static final String ST_BOOT_WALL = "st_boot_wall";

    private AppConfig cfg;
    private AlarmManager alarm;
    private boolean receiverRegistered = false;

    /** 已累计的亮屏时长（不含当前这一段）。 */
    private long accumulatedMs;
    /** 当前亮屏起点（elapsedRealtime），-1 表示当前息屏。 */
    private long screenOnSince = -1;
    /** 息屏起点，-1 表示未息屏。 */
    private long screenOffMark = -1;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                onScreenOn();
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                onScreenOff();
            }
        }
    };

    // ---------- 生命周期 ----------

    @Override
    public void onCreate() {
        super.onCreate();
        alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        cfg = AppConfig.load(this);
        startForeground(NOTIF_ID, buildNotification(cfg));
        registerScreenReceiver();
        reconcile(this);
        restoreState();
        beat(this);

        String action = intent == null ? null : intent.getAction();
        if (ACTION_TEST.equals(action)) {
            ReminderOverlay.show(this, cfg);
        } else if (ACTION_RESET.equals(action)) {
            resetTimer();
        } else if (ACTION_REMIND.equals(action)) {
            handleRemind(this);
        } else if (ACTION_TICK.equals(action)) {
            handleTick(this);
        } else {
            // ACTION_START / ACTION_RELOAD / 系统重启服务：按状态继续计时
            reschedule(this);
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户/清理工具把任务划掉时，保证计时与闹钟继续有效
        Log.i(TAG, "任务被移除，重新拉起计时");
        reschedule(this);
        scheduleTick(this);
        startSelfAsync(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "服务销毁，保留系统闹钟以确保提醒不丢");
        unregisterScreenReceiver();
        reschedule(this);
        scheduleTick(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static void startSelfAsync(final Context c) {
        new Thread(() -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ignored) {
                // 忽略
            }
            start(c);
        }).start();
    }

    // ---------- 计时状态 ----------

    private void restoreState() {
        SharedPreferences p = AppConfig.prefs(this);
        accumulatedMs = p.getLong(ST_ACC, 0L);
        screenOnSince = p.getLong(ST_ON_SINCE, -1L);
        screenOffMark = p.getLong(ST_OFF_MARK, -1L);
        long now = SystemClock.elapsedRealtime();
        if (screenOnSince > now) {
            screenOnSince = now;
        }
        if (screenOffMark > now) {
            screenOffMark = now;
        }
        persistState();
    }

    /**
     * 状态对账：按「当前屏幕状态」修正持久化状态。
     *
     * 作用：进程被系统清理期间发生的亮/熄屏切换无人记录，本方法在下次被唤醒时把缺口补上。
     * 策略偏保守——无法确认的亮屏时间不补计（宁可晚提醒，也不凭空多算）。
     */
    static void reconcile(Context c) {
        SharedPreferences p = AppConfig.prefs(c);
        long acc = p.getLong(ST_ACC, 0L);
        long onSince = p.getLong(ST_ON_SINCE, -1L);
        long offMark = p.getLong(ST_OFF_MARK, -1L);
        long now = SystemClock.elapsedRealtime();
        boolean on = isInteractive(c);

        if (on) {
            if (onSince < 0) {
                if (offMark >= 0) {
                    AppConfig cfg = AppConfig.load(c);
                    long offMs = Math.max(0L, now - offMark);
                    if (cfg.resetAfterScreenOffMillis() > 0 && offMs >= cfg.resetAfterScreenOffMillis()) {
                        acc = 0L;
                        Log.i(TAG, "对账：息屏 " + (offMs / 60000L) + " 分钟，计时清零");
                    }
                }
                // 进程死期间的亮屏时长无法确认，从现在重新开始计
                onSince = now;
            }
            offMark = -1L;
        } else {
            if (onSince >= 0) {
                acc += Math.max(0L, now - onSince);
                onSince = -1L;
            }
            if (offMark < 0) {
                offMark = now;
            }
        }
        p.edit()
                .putLong(ST_ACC, acc)
                .putLong(ST_ON_SINCE, onSince)
                .putLong(ST_OFF_MARK, offMark)
                .apply();
    }

    private void onScreenOn() {
        long now = SystemClock.elapsedRealtime();
        if (screenOffMark >= 0 && cfg != null && cfg.resetAfterScreenOffMillis() > 0
                && now - screenOffMark >= cfg.resetAfterScreenOffMillis()) {
            Log.i(TAG, "息屏超过 " + cfg.resetAfterScreenOffMinutes() + " 分钟，计时清零");
            accumulatedMs = 0;
        }
        screenOffMark = -1;
        if (screenOnSince < 0) {
            screenOnSince = now;
        }
        persistState();
        beat(this);
        reschedule(this);
    }

    private void onScreenOff() {
        long now = SystemClock.elapsedRealtime();
        if (screenOnSince >= 0) {
            accumulatedMs += now - screenOnSince;
            screenOnSince = -1;
        }
        screenOffMark = now;
        persistState();
        ReminderOverlay.dismiss(this);
        // 注意：这里不取消闹钟。息屏期间改挂不唤醒设备的闹钟，亮屏后由系统补投。
        reschedule(this);
        Log.i(TAG, "息屏，暂停计时；已累计 " + (watchingMs() / 60000) + " 分钟");
    }

    private long watchingMs() {
        long v = accumulatedMs;
        if (screenOnSince >= 0) {
            v += SystemClock.elapsedRealtime() - screenOnSince;
        }
        return v;
    }

    private void resetTimer() {
        resetPersisted(this);
        restoreState();
        reschedule(this);
        Log.i(TAG, "计时已重置");
    }

    private void persistState() {
        AppConfig.prefs(this).edit()
                .putLong(ST_ACC, accumulatedMs)
                .putLong(ST_ON_SINCE, screenOnSince)
                .putLong(ST_OFF_MARK, screenOffMark)
                .apply();
    }

    /** 写心跳，供看门狗判断服务是否存活。 */
    private static void beat(Context c) {
        AppConfig.prefs(c).edit().putLong(ST_HEARTBEAT, SystemClock.elapsedRealtime()).apply();
    }

    /** 记录一次「开机自启」发生的时间，供界面核对开机自启是否真的生效。 */
    static void markBoot(Context c) {
        String wall = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date());
        AppConfig.prefs(c).edit()
                .putLong(ST_BOOT_AT, SystemClock.elapsedRealtime())
                .putString(ST_BOOT_WALL, wall)
                .apply();
        Log.i(TAG, "记录开机自启：" + wall + "（开机后 " + (SystemClock.elapsedRealtime() / 1000L) + " 秒）");
    }

    /** 上次开机自启时的 elapsedRealtime；-1 表示从未记录过。 */
    public static long bootAtElapsedMs(Context c) {
        return AppConfig.prefs(c).getLong(ST_BOOT_AT, -1L);
    }

    /** 上次开机自启的墙上时间字符串；null 表示从未记录过。 */
    public static String bootWallClock(Context c) {
        return AppConfig.prefs(c).getString(ST_BOOT_WALL, null);
    }

    /** 距上次心跳过了多久（毫秒）。用于界面显示与自愈判断。 */
    public static long heartbeatAgeMs(Context c) {
        long hb = AppConfig.prefs(c).getLong(ST_HEARTBEAT, -1L);
        if (hb < 0) {
            return -1L;
        }
        return Math.max(0L, SystemClock.elapsedRealtime() - hb);
    }

    // ---------- 静态状态操作（供广播入口复用，避免依赖前台服务是否在运行） ----------

    /** 供界面展示用：读取持久化状态计算当前连续观看时长。 */
    public static long currentWatchingMs(Context c) {
        long acc = AppConfig.prefs(c).getLong(ST_ACC, 0L);
        long onSince = AppConfig.prefs(c).getLong(ST_ON_SINCE, -1L);
        long v = acc;
        if (onSince >= 0) {
            v += Math.max(0, SystemClock.elapsedRealtime() - onSince);
        }
        return v;
    }

    /** 清零并开始新一轮计时（按当前屏幕状态决定起点）。 */
    static void resetPersisted(Context c) {
        long now = SystemClock.elapsedRealtime();
        boolean on = isInteractive(c);
        AppConfig.prefs(c).edit()
                .remove(ST_ACC)
                .putLong(ST_ON_SINCE, on ? now : -1L)
                .putLong(ST_OFF_MARK, on ? -1L : now)
                .apply();
    }

    static boolean isInteractive(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            return pm.isInteractive();
        }
        return pm.isScreenOn();
    }

    // ---------- 系统定时器 ----------

    private static PendingIntent pi(Context c, String action, int req) {
        Intent i = new Intent(c, ActionReceiver.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, req, i, flags);
    }

    static PendingIntent remindPendingIntent(Context c) {
        return pi(c, ACTION_REMIND, REQ_REMIND);
    }

    static PendingIntent tickPendingIntent(Context c) {
        return pi(c, ACTION_TICK, REQ_TICK);
    }

    static void cancelAlarm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(remindPendingIntent(c));
        }
    }

    /** 按当前状态重新设定系统定时器；返回 true 表示已到点、当场触发了提醒。 */
    static boolean reschedule(Context c) {
        AppConfig cfg = AppConfig.load(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return false;
        }
        PendingIntent pi = remindPendingIntent(c);
        am.cancel(pi);
        if (!cfg.enabled()) {
            Log.i(TAG, "提醒已关闭，不设定提醒闹钟");
            scheduleTick(c);
            return false;
        }
        long remain = cfg.thresholdMillis() - currentWatchingMs(c);
        if (remain <= 0 && isInteractive(c)) {
            fireReminder(c);
            return true;
        }
        if (remain <= 0) {
            // 已超时但当前息屏：不弹，等亮屏后由闹钟补投再弹
            remain = 1000L;
        }
        long triggerAt = SystemClock.elapsedRealtime() + remain;
        try {
            if (isInteractive(c)) {
                // 亮屏：精确闹钟，到点必须准
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                }
            } else {
                // 息屏：不唤醒设备，等下次唤醒（通常是亮屏）时补投
                am.setWindow(AlarmManager.ELAPSED_REALTIME, triggerAt, 60_000L, pi);
            }
        } catch (SecurityException e) {
            // 个别盒子限制精确闹钟，退化为普通 set，误差可接受
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        }
        Log.i(TAG, "已设定提醒：" + (remain / 60000) + " 分 " + ((remain / 1000) % 60) + " 秒后");
        scheduleTick(c);
        return false;
    }

    /**
     * 看门狗闹钟：15 分钟一次。亮屏时用可唤醒的窗口闹钟，息屏时不唤醒设备。
     * 它是「进程被清理后仍能自愈」的关键：闹钟由系统持有，与进程生死无关。
     */
    static void scheduleTick(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = tickPendingIntent(c);
        long at = SystemClock.elapsedRealtime() + TICK_INTERVAL_MS;
        long window = TICK_INTERVAL_MS / 3L;
        try {
            if (isInteractive(c)) {
                am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, window, pi);
            } else {
                am.setWindow(AlarmManager.ELAPSED_REALTIME, at, window, pi);
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        }
    }

    /** 看门狗到点：对账 → 补设闹钟 → 必要时拉起服务 → 超时且亮屏则立即提醒。 */
    static void handleTick(Context c) {
        reconcile(c);
        AppConfig cfg = AppConfig.load(c);
        if (cfg.enabled() && isInteractive(c)
                && currentWatchingMs(c) >= cfg.thresholdMillis()) {
            fireReminder(c);
        } else {
            reschedule(c);
        }
        // 服务不在了就拉起来，让亮/熄屏事件重新有人处理
        long age = heartbeatAgeMs(c);
        if (age < 0 || age > TICK_INTERVAL_MS * 2L) {
            Log.i(TAG, "看门狗：服务心跳超时（" + age + " ms），重新拉起");
            start(c);
        } else {
            beat(c);
        }
        scheduleTick(c);
    }

    /** 提醒闹钟到点：先对账，确认亮屏且已超时才弹。 */
    static void handleRemind(Context c) {
        reconcile(c);
        AppConfig cfg = AppConfig.load(c);
        if (!cfg.enabled()) {
            Log.i(TAG, "提醒已关闭，忽略本次到点");
            scheduleTick(c);
            return;
        }
        if (!isInteractive(c)) {
            Log.i(TAG, "到点但当前息屏，改挂亮屏后补投");
            reschedule(c);
            return;
        }
        if (currentWatchingMs(c) < cfg.thresholdMillis()) {
            reschedule(c);
            return;
        }
        fireReminder(c);
    }

    /** 展示浮层并开启下一轮计时。 */
    static void fireReminder(Context c) {
        AppConfig cfg = AppConfig.load(c);
        ReminderOverlay.show(c, cfg);
        Log.i(TAG, "触发提醒：" + cfg.message());
        // 提醒后开启新一轮计时；真正休息（息屏）超过阈值同样会清零
        resetPersisted(c);
        reschedule(c);
    }

    // ---------- 广播注册 ----------

    private void registerScreenReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);
        receiverRegistered = true;
    }

    private void unregisterScreenReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException ignored) {
            // 已注销
        }
        receiverRegistered = false;
    }

    // ---------- 前台服务通知 ----------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, getString(R.string.app_name),
                NotificationManager.IMPORTANCE_MIN);
        ch.setDescription(getString(R.string.notif_channel_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(AppConfig c) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 1002, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_stat_reminder)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_content, c.thresholdMinutes()))
                .setContentIntent(pi)
                .setOngoing(true)
                .setLocalOnly(true)
                .setPriority(Notification.PRIORITY_MIN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setBadgeIconType(Notification.BADGE_ICON_NONE);
        }
        return b.build();
    }

    // ---------- 外部入口 ----------

    static void start(Context c) {
        Intent i = new Intent(c, WatchTimerService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "启动服务失败", e);
        }
    }

    static void send(Context c, String action) {
        try {
            c.startService(new Intent(c, WatchTimerService.class).setAction(action));
        } catch (Exception e) {
            Log.w(TAG, "发送指令失败: " + action, e);
        }
    }
}
