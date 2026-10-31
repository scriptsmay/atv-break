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

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 每日累计亮屏时长（与「连续观看」计时线完全隔离的第二条计时线）。
 *
 * 口径：只累计亮屏时长，跨 00:00 按墙钟切分归属日；状态写 SharedPreferences，
 * 按日落盘到 {@code daily_history}（{@code {"yyyy-MM-dd": 毫秒}}，保留 365 天）。
 *
 * 时间使用两条时钟各司其职：
 * - 时长计算用单调时钟 {@code elapsedRealtime}，不受用户改时间 / NTP 校时影响；
 * - 跨天切分用墙钟判断「哪一段属于哪一天」。
 *
 * {@link #rollDayIfNeeded} 是纯函数（时间与历史表都由调用方注入），便于单元测试与
 * adb 调试注入跨天场景。
 */
public final class DailyWatchStats {

    private static final String TAG = "AtvBreak.Daily";

    static final String K_DAY = "daily_day";
    static final String K_ACC_MS = "daily_acc_ms";
    static final String K_ON_SINCE = "daily_on_since";
    static final String K_ON_SINCE_WALL = "daily_on_since_wall";
    static final String K_FIRED_DAY = "daily_fired_day";
    static final String K_FIRED_AT_WALL = "daily_fired_at_wall";
    static final String K_FIRED_AT_ELAPSED = "daily_fired_at_elapsed";
    static final String K_HISTORY = "daily_history";

    /** 历史保留天数。 */
    static final int HISTORY_DAYS = 365;

    private static final String DAY_PATTERN = "yyyy-MM-dd";

    private DailyWatchStats() {
    }

    /** 每日累计状态（不含历史表）。 */
    public static final class State {
        /** 当前统计归属日 yyyy-MM-dd；null 表示尚未初始化。 */
        public String day;
        /** 当日已累计毫秒（不含当前亮屏段）。 */
        public long accMs;
        /** 当前亮屏段起点 elapsedRealtime；-1 表示息屏。 */
        public long onSinceElapsed = -1L;
        /** 当前亮屏段起点墙钟毫秒（仅用于跨天切分）；-1 表示息屏。 */
        public long onSinceWall = -1L;
    }

    /** 跨天结算结果。 */
    public static final class Roll {
        /** 本次调用是否发生了跨天。 */
        public boolean rolled;
        /** 被结算的上一日日期；未跨天时为 null。 */
        public String prevDay;
        /** 上一日的最终累计毫秒。 */
        public long prevTotal;
    }

    // ---------- 纯逻辑 ----------

    /**
     * 若归属日已不是今天，则结算上一日并把跨午夜的那一段正确拆开。
     *
     * @param nowElapsed 当前 elapsedRealtime
     * @param nowWall    当前墙钟毫秒
     * @param history    历史表，跨天时写入上一日与今日的值并淘汰过期条目
     */
    public static Roll rollDayIfNeeded(State s, long nowElapsed, long nowWall,
                                       Map<String, Long> history) {
        Roll r = new Roll();
        String today = dayKey(nowWall);
        if (s.day == null || s.day.isEmpty()) {
            // 首次运行：直接归属今天，不产生历史
            s.day = today;
            return r;
        }
        if (s.day.equals(today)) {
            return r;
        }

        String prevDay = s.day;
        long prevTotal;
        if (s.onSinceElapsed >= 0) {
            // 当前亮屏段 = [onSinceWall, nowWall]，午夜之后的部分 carry 归今天。
            //
            // 注意：carry 必须留在「当前亮屏段」里，不能同时写进 accMs——否则
            // todayTotal = accMs + (now - onSince) 会把这段算两遍（跨天当天的累计直接翻倍）。
            // 段长用单调时钟计算，carry 再按段长截断：墙钟被校时/切时区跳变时，
            // 不会把不存在的时长塞进今天。
            long midnight = midnightWall(nowWall);
            long segStart = Math.min(s.onSinceWall, nowWall);   // 防墙钟回拨
            long segElapsed = Math.max(0L, nowElapsed - s.onSinceElapsed);
            long carryWall = Math.max(0L, nowWall - Math.max(segStart, midnight));
            long carry = Math.min(carryWall, segElapsed);
            prevTotal = s.accMs + (segElapsed - carry);
            s.accMs = 0L;
            s.onSinceElapsed = nowElapsed - carry;
            s.onSinceWall = Math.max(segStart, midnight);
        } else {
            prevTotal = s.accMs;
            s.accMs = 0L;
        }
        s.day = today;

        r.rolled = true;
        r.prevDay = prevDay;
        r.prevTotal = prevTotal;

        history.put(prevDay, prevTotal);
        pruneHistory(history, today);
        history.put(today, s.accMs);
        return r;
    }

    /** 淘汰早于「今天 - 365 天」的条目。 */
    public static void pruneHistory(Map<String, Long> history, String todayKey) {
        Calendar limit = calendarOf(todayKey);
        if (limit == null) {
            return;
        }
        limit.add(Calendar.DAY_OF_YEAR, -HISTORY_DAYS);
        long min = limit.getTimeInMillis();
        Iterator<Map.Entry<String, Long>> it = history.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            Calendar c = calendarOf(e.getKey());
            if (c == null) {
                it.remove();   // 无法解析的脏 key 直接清掉
            } else if (c.getTimeInMillis() < min) {
                it.remove();
            }
        }
    }

    /** 墙钟毫秒 -> yyyy-MM-dd（本机时区）。 */
    public static String dayKey(long wallMs) {
        return format(new Date(wallMs));
    }

    /** 前一日日期串；无法解析时返回 null。 */
    public static String previousDayKey(String key) {
        Calendar c = calendarOf(key);
        if (c == null) {
            return null;
        }
        c.add(Calendar.DAY_OF_YEAR, -1);
        return format(c.getTime());
    }

    /** 该墙钟时刻所在日的 00:00 墙钟毫秒。 */
    public static long midnightWall(long wallMs) {
        Calendar c = new GregorianCalendar();
        c.setTimeInMillis(wallMs);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String format(Date d) {
        return new SimpleDateFormat(DAY_PATTERN, Locale.US).format(d);
    }

    private static Calendar calendarOf(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        SimpleDateFormat f = new SimpleDateFormat(DAY_PATTERN, Locale.US);
        f.setLenient(false);
        try {
            Date d = f.parse(key);
            Calendar c = Calendar.getInstance(TimeZone.getDefault());
            c.setTime(d);
            return c;
        } catch (ParseException e) {
            return null;
        }
    }

    // ---------- 持久化 ----------

    private static SharedPreferences prefs(Context c) {
        return AppConfig.prefs(c);
    }

    /** 读取状态（不含历史）。 */
    public static State load(Context c) {
        SharedPreferences p = prefs(c);
        State s = new State();
        s.day = p.getString(K_DAY, null);
        s.accMs = p.getLong(K_ACC_MS, 0L);
        s.onSinceElapsed = p.getLong(K_ON_SINCE, -1L);
        s.onSinceWall = p.getLong(K_ON_SINCE_WALL, -1L);
        return s;
    }

    private static void save(Context c, State s) {
        prefs(c).edit()
                .putString(K_DAY, s.day)
                .putLong(K_ACC_MS, s.accMs)
                .putLong(K_ON_SINCE, s.onSinceElapsed)
                .putLong(K_ON_SINCE_WALL, s.onSinceWall)
                .apply();
    }

    /**
     * 读取历史表。JSON 损坏或不是对象时整体视为空——一个坏 key 不能拖垮计时。
     */
    public static Map<String, Long> loadHistory(Context c) {
        Map<String, Long> map = new HashMap<>();
        String raw = prefs(c).getString(K_HISTORY, null);
        if (raw == null || raw.isEmpty()) {
            return map;
        }
        try {
            JSONObject o = new JSONObject(raw);
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                long v = o.optLong(k, -1L);
                if (v >= 0L) {
                    map.put(k, v);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "daily_history 解析失败，视为空", e);
            map.clear();
        }
        return map;
    }

    static void saveHistory(Context c, Map<String, Long> history) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Long> e : history.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
        } catch (Exception e) {
            Log.w(TAG, "写入 daily_history 失败", e);
            return;
        }
        prefs(c).edit().putString(K_HISTORY, o.toString()).apply();
    }

    // ---------- 事件钩子 ----------

    /** 亮屏：先跨天结算，再从当前时刻开始新的一段。 */
    public static void onScreenOn(Context c) {
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        long nowWall = System.currentTimeMillis();
        State s = load(c);
        Map<String, Long> history = loadHistory(c);
        Roll r = rollDayIfNeeded(s, nowElapsed, nowWall, history);
        if (s.onSinceElapsed < 0) {
            s.onSinceElapsed = nowElapsed;
            s.onSinceWall = nowWall;
        }
        save(c, s);
        if (r.rolled) {
            saveHistory(c, history);
        }
    }

    /**
     * 息屏：<b>先跨天结算</b>再把当前段累加进去。
     *
     * 不加第一步会出错：23:40 亮屏、00:30 息屏且期间无事件时，直接累加会把整段
     * 写进「今天」，随后下一次 roll 又整体结转成上一日——今日少算、上一日多算。
     */
    public static void onScreenOff(Context c) {
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        long nowWall = System.currentTimeMillis();
        State s = load(c);
        Map<String, Long> history = loadHistory(c);
        Roll r = rollDayIfNeeded(s, nowElapsed, nowWall, history);
        if (s.onSinceElapsed >= 0) {
            s.accMs += Math.max(0L, nowElapsed - s.onSinceElapsed);
            s.onSinceElapsed = -1L;
            s.onSinceWall = -1L;
        }
        save(c, s);
        // 息屏时把当日最新值落盘，保证进程被杀后当日进度不丢
        history.put(s.day, s.accMs);
        saveHistory(c, history);
        if (r.rolled) {
            Log.i(TAG, "跨天结算：" + r.prevDay + " 共 " + (r.prevTotal / 60000L) + " 分钟");
        }
    }

    /** 当前累计（含正在进行的亮屏段）；顺带完成跨天结算。 */
    public static long todayTotalMs(Context c) {
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        long nowWall = System.currentTimeMillis();
        State s = load(c);
        Map<String, Long> history = loadHistory(c);
        Roll r = rollDayIfNeeded(s, nowElapsed, nowWall, history);
        long total = s.accMs;
        if (s.onSinceElapsed >= 0) {
            total += Math.max(0L, nowElapsed - s.onSinceElapsed);
        }
        if (r.rolled) {
            save(c, s);
            saveHistory(c, history);
            Log.i(TAG, "跨天结算：" + r.prevDay + " 共 " + (r.prevTotal / 60000L) + " 分钟");
        }
        return Math.max(0L, total);
    }

    /**
     * 调试用：注入墙钟，强制走一次跨天结算（验证午夜切分，不必真等到 00:00）。
     *
     * 注入后必须把「当前亮屏段」重置为真实时刻：否则段起点会停在注入的那个墙钟上，
     * 下一次真实跨天结算会把「注入时刻到现在」整段当成今天的量，凭空多算几个小时。
     */
    public static void rollDay(Context c, long nowWall) {
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        long realWall = System.currentTimeMillis();
        State s = load(c);
        Map<String, Long> history = loadHistory(c);
        Roll r = rollDayIfNeeded(s, nowElapsed, nowWall, history);
        // 把亮屏段重新锚到真实时刻，避免调试注入污染后续累计
        if (s.onSinceElapsed >= 0) {
            s.onSinceElapsed = nowElapsed;
            s.onSinceWall = realWall;
        }
        save(c, s);
        saveHistory(c, history);
        if (r.rolled) {
            Log.i(TAG, "调试跨天结算：" + r.prevDay + " 共 " + (r.prevTotal / 60000L) + " 分钟");
        }
    }

    /** 今日累计清零（不影响历史）。 */
    public static void resetToday(Context c) {
        State s = load(c);
        s.accMs = 0L;
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        if (s.onSinceElapsed >= 0) {
            s.onSinceElapsed = nowElapsed;
            s.onSinceWall = System.currentTimeMillis();
        }
        save(c, s);
    }

    /**
     * 开机后显式失效：{@code elapsedRealtime} 从 0 重新开始，持久化的段起点会变成
     * 「未来时刻」。仅靠数值比较不可靠（保存值若早于重启后已过的时间，会凭空多算），
     * 因此直接清空段起点；当日累计保留，重启瞬间的亮屏段不补计。
     */
    public static void clearSessionStart(Context c) {
        prefs(c).edit()
                .putLong(K_ON_SINCE, -1L)
                .putLong(K_ON_SINCE_WALL, -1L)
                .apply();
    }

    /**
     * 与连续计时同源的状态对账：进程被杀期间的亮屏段无法确认，<b>不补计</b>
     * （宁可少算）。顺带完成跨天结算。
     */
    public static void reconcile(Context c) {
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        long nowWall = System.currentTimeMillis();
        State s = load(c);
        Map<String, Long> history = loadHistory(c);
        Roll r = rollDayIfNeeded(s, nowElapsed, nowWall, history);
        boolean on = WatchTimerService.isInteractive(c);
        if (on) {
            if (s.onSinceElapsed < 0) {
                // 进程死期间的亮屏时长无法确认，从现在重新开始计
                s.onSinceElapsed = nowElapsed;
                s.onSinceWall = nowWall;
            }
        } else if (s.onSinceElapsed >= 0) {
            s.accMs += Math.max(0L, nowElapsed - s.onSinceElapsed);
            s.onSinceElapsed = -1L;
            s.onSinceWall = -1L;
        }
        save(c, s);
        if (r.rolled) {
            history.put(s.day, s.accMs);
            saveHistory(c, history);
            Log.i(TAG, "跨天结算：" + r.prevDay + " 共 " + (r.prevTotal / 60000L) + " 分钟");
        }
    }

    /** 兜底：段起点晚于当前 elapsedRealtime 时视为异常值（进程重启但设备未重启）。 */
    public static void sanitizeSessionStart(Context c) {
        long now = android.os.SystemClock.elapsedRealtime();
        State s = load(c);
        if (s.onSinceElapsed > now) {
            s.onSinceElapsed = -1L;
            s.onSinceWall = -1L;
            save(c, s);
        }
    }

    // ---------- 已提醒标记 ----------

    /** 必须在 {@code DailyMaskOverlay.show()} 确认成功之后调用。 */
    public static void markFired(Context c) {
        prefs(c).edit()
                .putString(K_FIRED_DAY, dayKey(System.currentTimeMillis()))
                .putLong(K_FIRED_AT_WALL, System.currentTimeMillis())
                .putLong(K_FIRED_AT_ELAPSED, android.os.SystemClock.elapsedRealtime())
                .apply();
    }

    public static boolean firedToday(Context c) {
        String day = prefs(c).getString(K_FIRED_DAY, null);
        return dayKey(System.currentTimeMillis()).equals(day);
    }

    /** 上次弹 mask 的 elapsedRealtime；-1 表示从未弹过（或已不可用）。 */
    public static long lastFiredElapsed(Context c) {
        return prefs(c).getLong(K_FIRED_AT_ELAPSED, -1L);
    }

    /**
     * 到点处理时是否应该真的弹出。
     *
     * 「今天已弹过」不等于「今天不再弹」——超限后还要按重复间隔再弹。因此这里区分：
     * - 今天没弹过 → 弹；
     * - 弹过但重复间隔为 0（当天只提醒一次）→ 不弹；
     * - 弹过且距上次弹出已满一个重复间隔 → 弹；
     * - 弹过但间隔还没到（闹钟早到 / 手动触发）→ 不弹，等排定的时间点。
     *
     * @param firedToday        今天是否已成功弹过
     * @param repeatMs          重复间隔毫秒，0 表示不重复
     * @param nowElapsed        当前 elapsedRealtime
     * @param lastFiredElapsed  上次弹出的 elapsedRealtime，-1 表示无记录
     */
    public static boolean shouldFireNow(boolean firedToday, long repeatMs,
                                        long nowElapsed, long lastFiredElapsed) {
        if (!firedToday) {
            return true;
        }
        if (repeatMs <= 0L) {
            return false;
        }
        if (lastFiredElapsed < 0L) {
            return false;
        }
        return nowElapsed - lastFiredElapsed >= repeatMs;
    }

    // ---------- 历史查询 ----------

    /** 指定日期的累计毫秒；无记录返回 -1（界面显示「—」）。 */
    public static long historyFor(Context c, String date) {
        if (date == null) {
            return -1L;
        }
        Long v = loadHistory(c).get(date);
        return v == null ? -1L : v;
    }

    /** 昨日累计毫秒；无记录返回 -1。 */
    public static long yesterdayMs(Context c) {
        String today = dayKey(System.currentTimeMillis());
        return historyFor(c, previousDayKey(today));
    }

    public static int historySize(Context c) {
        return loadHistory(c).size();
    }
}
