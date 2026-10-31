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
    /** 每日累计线到点（与连续提醒的 REMIND 语义与生命周期都不同，独立动作/请求码）。 */
    public static final String ACTION_DAILY_REMIND = "com.atvbreak.action.DAILY_REMIND";
    /** 验收用：立即弹一次全屏 mask（不写已提醒标记、不改变累计）。 */
    public static final String ACTION_TEST_DAILY_MASK = "com.atvbreak.action.TEST_DAILY_MASK";
    /** 调试用：注入墙钟，验证跨天切分。 */
    public static final String ACTION_DAILY_ROLL_DAY = "com.atvbreak.action.DAILY_ROLL_DAY";

    private static final String CHANNEL_ID = "atv_break_timer";
    private static final int NOTIF_ID = 1;
    private static final int REQ_REMIND = 1001;
    private static final int REQ_TICK = 1003;
    private static final int REQ_DAILY_REMIND = 1004;

    /** 看门狗周期：15 分钟。开销接近零，只做一次状态对账。 */
    static final long TICK_INTERVAL_MS = 15L * 60L * 1000L;

    /**
     * DAILY 到点但未能展示（前台 / 息屏 / 权限缺失）时的首次重试间隔。
     * 连续失败时按 {@link #nextRetryDelay(long)} 指数退避，避免「每分钟一次唤醒」永不停歇。
     */
    static final long DAILY_RETRY_MS = 60_000L;
    /** 退避上限：不超过看门狗周期，保证状态恢复后最迟一个 TICK 内重新排上。 */
    static final long DAILY_RETRY_MAX_MS = TICK_INTERVAL_MS;

    /** 连续失败第 n 次（从 1 开始）后的重试间隔：1 分 → 2 分 → 4 分 → … → 15 分封顶。 */
    static long nextRetryDelay(int failures) {
        long delay = DAILY_RETRY_MS;
        for (int i = 1; i < failures; i++) {
            delay *= 2L;
            if (delay >= DAILY_RETRY_MAX_MS) {
                return DAILY_RETRY_MAX_MS;
            }
        }
        return delay;
    }

    private static final String ST_ACC = "st_accumulated_ms";
    private static final String ST_ON_SINCE = "st_screen_on_since";
    private static final String ST_OFF_MARK = "st_screen_off_mark";
    /** 服务心跳（elapsedRealtime）。看门狗据此判断服务是否还活着。 */
    private static final String ST_HEARTBEAT = "st_heartbeat";
    /** 上次收到开机广播的时刻（elapsedRealtime / 墙上时间）。 */
    private static final String ST_BOOT_AT = "st_boot_at";
    private static final String ST_BOOT_WALL = "st_boot_wall";
    /** DAILY 连续未展示次数（退避用，落盘以便进程重启后继续退避）。 */
    private static final String K_DAILY_FAILS = "daily_fail_count";

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
        } else if (ACTION_DAILY_REMIND.equals(action)) {
            handleDailyRemind(this);
        } else if (ACTION_TICK.equals(action)) {
            handleTick(this);
        } else {
            // ACTION_START / ACTION_RELOAD / 系统重启服务：按状态继续计时
            reschedule(this);
            scheduleDaily(this);
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
        // elapsedRealtime 重启后归零，持久化的起点会变成「未来时刻」。
        // 仅靠 > now 比较不可靠（保存值若早于重启后已过的时间，会凭空多算），
        // 因此异常值一律显式失效，由随后的 reconcile 按当前屏幕状态重建起点。
        long now = SystemClock.elapsedRealtime();
        if (screenOnSince > now) {
            screenOnSince = -1L;
        }
        if (screenOffMark > now) {
            screenOffMark = -1L;
        }
        persistState();
        DailyWatchStats.sanitizeSessionStart(this);
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
        DailyWatchStats.reconcile(c);
    }

    /**
     * 开机后显式失效：elapsedRealtime 归零使持久化的段起点失效。
     * 当日累计保留，重启瞬间的亮屏段不补计。
     */
    static void clearSessionStart(Context c) {
        AppConfig.prefs(c).edit()
                .putLong(ST_ON_SINCE, -1L)
                .putLong(ST_OFF_MARK, -1L)
                .apply();
        DailyWatchStats.clearSessionStart(c);
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
        DailyWatchStats.onScreenOn(this);
        beat(this);
        reschedule(this);
        scheduleDaily(this);
    }

    private void onScreenOff() {
        long now = SystemClock.elapsedRealtime();
        if (screenOnSince >= 0) {
            accumulatedMs += now - screenOnSince;
            screenOnSince = -1;
        }
        screenOffMark = now;
        persistState();
        DailyWatchStats.onScreenOff(this);
        ReminderOverlay.dismiss(this);
        DailyMaskOverlay.dismiss(this);
        // 注意：这里不取消闹钟。息屏期间改挂不唤醒设备的闹钟，亮屏后由系统补投。
        reschedule(this);
        scheduleDaily(this);
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

    static PendingIntent dailyPendingIntent(Context c) {
        return pi(c, ACTION_DAILY_REMIND, REQ_DAILY_REMIND);
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
            // 主开关是总闸门：每日线一并停掉，并收起可能正在显示的全屏 mask
            stopDaily(c);
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
        // 每日线同样在看门狗里自愈：进程被杀 / 闹钟丢失后重新排上
        scheduleDaily(c);
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

    /**
     * 展示浮层并开启下一轮计时。
     *
     * 提醒仲裁：全屏 mask 显示期间不叠小浮层、不叠第二路提示音，只把连续计时清零
     * （视为已提醒）——mask 已经完成了「让用户看一眼」的目的，再补一个小浮层属于重复打扰。
     */
    static void fireReminder(Context c) {
        if (DailyMaskOverlay.isShowing()) {
            Log.i(TAG, "全屏提醒正在显示，本次连续提醒只清零不弹层");
            resetPersisted(c);
            reschedule(c);
            return;
        }
        AppConfig cfg = AppConfig.load(c);
        ReminderOverlay.show(c, cfg);
        Log.i(TAG, "触发提醒：" + cfg.message());
        // 提醒后开启新一轮计时；真正休息（息屏）超过阈值同样会清零
        resetPersisted(c);
        reschedule(c);
    }

    // ---------- 每日累计线：调度 ----------

    /** 取消 DAILY 闹钟。 */
    static void cancelDaily(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(dailyPendingIntent(c));
        }
    }

    /**
     * 按当前状态排下一次 DAILY 闹钟。
     *
     * 三个候选触发点取最早：① 距阈值的剩余时长；② 距零点（到点后 roll 清零并重排，
     * 天然完成跨天切换，不需要额外的零点闹钟）；③ 上次弹出 + 重复间隔。
     * 算出的触发点若已早于当前 elapsedRealtime（闹钟丢失、休眠恢复、进程被杀期间跨过），
     * 不再排到过去，而是立即触发一次。
     */
    static void scheduleDaily(Context c) {
        // 成功排上（或已关闭）后清空退避计数
        AppConfig.prefs(c).edit().remove(K_DAILY_FAILS).apply();
        scheduleDaily(c, true);
    }

    /**
     * @param allowImmediate false 时即使触发点已到也至少推迟 {@link #DAILY_RETRY_MS}，
     *                       避免「到点 → 处理 → 再排 → 又到点」的自旋
     */
    static void scheduleDaily(Context c, boolean allowImmediate) {
        AppConfig cfg = AppConfig.load(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = dailyPendingIntent(c);
        am.cancel(pi);

        if (!cfg.dailyActive()) {
            Log.i(TAG, "每日提醒未启用，取消 DAILY 闹钟并收起全屏提醒");
            DailyMaskOverlay.dismiss(c);
            return;
        }

        long nowElapsed = SystemClock.elapsedRealtime();
        long nowWall = System.currentTimeMillis();
        long total = DailyWatchStats.todayTotalMs(c);
        long remainToThreshold = cfg.dailyThresholdMillis() - total;
        long untilMidnight = Math.max(0L, DailyWatchStats.midnightWall(nowWall) + 86_400_000L - nowWall);

        long delay;
        if (remainToThreshold > 0L) {
            // 未到阈值：阈值剩余与零点取早者
            delay = Math.min(remainToThreshold, untilMidnight);
        } else if (!DailyWatchStats.firedToday(c)) {
            // 已超阈值但今天还没弹过：立即触发
            delay = 0L;
        } else if (cfg.dailyRepeatMillis() > 0L) {
            // 已弹过且允许重复：以「上次弹出时刻」为基准，避免误差累积
            long last = DailyWatchStats.lastFiredElapsed(c);
            if (last < 0L) {
                delay = 0L;
            } else {
                delay = last + cfg.dailyRepeatMillis() - nowElapsed;
                // 重复间隔已过（例如刚息屏再亮屏）：立即补弹，而不是排到过去
                if (delay < 0L) {
                    delay = 0L;
                }
                // 不跨过零点：到零点先结算
                delay = Math.min(delay, untilMidnight);
            }
        } else {
            // 不重复：次日零点再排
            delay = untilMidnight;
        }

        if (delay <= 0L && allowImmediate) {
            Log.i(TAG, "DAILY 触发点已到，立即处理");
            handleDailyRemind(c);
            return;
        }
        if (delay <= 0L) {
            delay = nextRetryDelay(bumpFailures(c));
        }

        long triggerAt = nowElapsed + delay;
        try {
            if (isInteractive(c)) {
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
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        }
        Log.i(TAG, "已设定每日提醒：" + (delay / 60000) + " 分 " + ((delay / 1000) % 60) + " 秒后");
    }

    /**
     * DAILY 闹钟到点：结算跨天 → 校验开关 → 校验阈值 → 防重复 → 前台判定 → 弹 mask。
     *
     * 只有 {@link DailyMaskOverlay#show} 确认成功后才写 {@code daily_fired_day}；
     * 未授予悬浮窗权限导致展示失败时不得写，否则用户当天再也收不到提醒。
     */
    static void handleDailyRemind(Context c) {
        AppConfig cfg = AppConfig.load(c);
        if (!cfg.dailyActive()) {
            Log.i(TAG, "每日提醒已关闭，忽略本次到点");
            cancelDaily(c);
            return;
        }
        long total = DailyWatchStats.todayTotalMs(c);
        if (total < cfg.dailyThresholdMillis()) {
            scheduleDaily(c, false);
            return;
        }
        // 「今天已弹过」不等于「今天不再弹」：超限后还要按重复间隔再弹
        if (!DailyWatchStats.shouldFireNow(DailyWatchStats.firedToday(c), cfg.dailyRepeatMillis(),
                SystemClock.elapsedRealtime(), DailyWatchStats.lastFiredElapsed(c))) {
            scheduleDaily(c, false);
            return;
        }
        if (MainActivity.isInForeground()) {
            Log.i(TAG, "应用在前台，不弹全屏提醒（状态卡改为橙色提示）");
            scheduleDaily(c, false);
            return;
        }
        if (!isInteractive(c)) {
            Log.i(TAG, "到点但当前息屏，改挂亮屏后补投");
            scheduleDaily(c, false);
            return;
        }
        if (DailyMaskOverlay.show(c, cfg, total)) {
            DailyWatchStats.markFired(c);
            Log.i(TAG, "每日提醒已弹出，今日累计 " + (total / 60000L) + " 分钟");
            // 按重复间隔排下一次
            scheduleDaily(c, false);
        } else {
            Log.w(TAG, "全屏提醒展示失败，不写已提醒标记，稍后重试");
            // 未展示成功：不要等到下一个重复间隔，尽快重试
            scheduleDaily(c, false);
        }
    }

    /** 记录一次「到点但没能展示」并返回连续失败次数。 */
    private static int bumpFailures(Context c) {
        int n = AppConfig.prefs(c).getInt(K_DAILY_FAILS, 0) + 1;
        AppConfig.prefs(c).edit().putInt(K_DAILY_FAILS, n).apply();
        return n;
    }

    /** 关闭开关时调用：取消闹钟并收起正在显示的全屏 mask。 */
    static void stopDaily(Context c) {
        cancelDaily(c);
        DailyMaskOverlay.dismiss(c);
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
