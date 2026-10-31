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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * 每日累计的跨天切分（纯逻辑，不依赖 Android）。
 *
 * 时间全部按本机时区构造，避免测试机时区不同导致断言漂移。
 */
public class DailyWatchStatsTest {

    private static final long MIN = 60_000L;
    private static final long HOUR = 60L * MIN;

    /** 本机时区下某天某时刻的墙钟毫秒。 */
    private static long wall(int year, int month1, int day, int hour, int minute) {
        Calendar c = new GregorianCalendar(TimeZone.getDefault());
        c.clear();
        c.set(year, month1 - 1, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static DailyWatchStats.State freshState() {
        return new DailyWatchStats.State();
    }

    private static Map<String, Long> history() {
        return new HashMap<>();
    }

    // ---------- 同日 ----------

    @Test
    public void sameDayDoesNotRoll() {
        DailyWatchStats.State s = freshState();
        Map<String, Long> h = history();
        long noon = wall(2026, 10, 30, 12, 0);
        DailyWatchStats.Roll r = DailyWatchStats.rollDayIfNeeded(s, 1_000L, noon, h);
        assertFalse(r.rolled);
        assertEquals("2026-10-30", s.day);
        assertTrue(h.isEmpty());
    }

    // ---------- 跨天：亮屏跨午夜 ----------

    @Test
    public void screenOnAcrossMidnightSplitsSegment() {
        DailyWatchStats.State s = freshState();
        Map<String, Long> h = history();
        long sinceWall = wall(2026, 10, 30, 23, 40);
        long nowWall = wall(2026, 10, 31, 0, 30);
        s.day = "2026-10-30";
        s.accMs = 0L;
        s.onSinceElapsed = 500_000L;
        s.onSinceWall = sinceWall;

        DailyWatchStats.Roll r = DailyWatchStats.rollDayIfNeeded(s, 500_000L + 50L * MIN, nowWall, h);

        assertTrue(r.rolled);
        assertEquals("2026-10-30", r.prevDay);
        assertEquals("昨日应记 20 分钟", 20L * MIN, r.prevTotal);
        assertEquals("今日应记 30 分钟", 30L * MIN, s.accMs);
        assertEquals("2026-10-31", s.day);
        // 当前亮屏段只保留午夜之后的部分
        assertEquals(nowWall - 30L * MIN, s.onSinceWall);
        assertEquals(500_000L + 50L * MIN - 30L * MIN, s.onSinceElapsed);
        // 历史落盘
        assertEquals(Long.valueOf(20L * MIN), h.get("2026-10-30"));
        assertEquals(Long.valueOf(30L * MIN), h.get("2026-10-31"));
    }

    @Test
    public void accumulatedPlusSegmentOnPreviousDay() {
        DailyWatchStats.State s = freshState();
        Map<String, Long> h = history();
        long sinceWall = wall(2026, 10, 30, 23, 30);
        long nowWall = wall(2026, 10, 31, 0, 10);
        s.day = "2026-10-30";
        s.accMs = 1L * HOUR;          // 23:30 前已累计 1 小时
        s.onSinceElapsed = 10_000L;
        s.onSinceWall = sinceWall;

        DailyWatchStats.Roll r = DailyWatchStats.rollDayIfNeeded(s, 10_000L + 40L * MIN, nowWall, h);

        assertEquals("上一日 = 1 小时 + 23:30~00:00 的 30 分钟", 1L * HOUR + 30L * MIN, r.prevTotal);
        assertEquals("今日归零后只有 00:00~00:10", 10L * MIN, s.accMs);
    }

    @Test
    public void segmentStartingAfterMidnightBelongsToToday() {
        DailyWatchStats.State s = freshState();
        Map<String, Long> h = history();
        // 归属日还停在昨天，但亮屏段是今天 00:05 才开始的（例如 00:05 才亮屏）
        long sinceWall = wall(2026, 10, 31, 0, 5);
        long nowWall = wall(2026, 10, 31, 0, 35);
        s.day = "2026-10-30";
        s.accMs = 0L;
        s.onSinceElapsed = 1_000L;
        s.onSinceWall = sinceWall;

        DailyWatchStats.Roll r = DailyWatchStats.rollDayIfNeeded(s, 1_000L + 30L * MIN, nowWall, h);

        assertEquals("上一日不增", 0L, r.prevTotal);
        assertEquals("整段归今天", 30L * MIN, s.accMs);
    }

    // ---------- 跨天：息屏状态 ----------

    @Test
    public void screenOffAcrossMidnightCarriesAccumulated() {
        DailyWatchStats.State s = freshState();
        Map<String, Long> h = history();
        s.day = "2026-10-30";
        s.accMs = 1L * HOUR;
        s.onSinceElapsed = -1L;

        DailyWatchStats.Roll r = DailyWatchStats.rollDayIfNeeded(
                s, 5_000L, wall(2026, 10, 31, 8, 0), h);

        assertTrue(r.rolled);
        assertEquals(1L * HOUR, r.prevTotal);
        assertEquals(0L, s.accMs);
        assertEquals("2026-10-31", s.day);
        assertEquals(Long.valueOf(0L), h.get("2026-10-31"));
    }

    @Test
    public void multiDayGapLeavesPreviousDayAbsent() {
        DailyWatchStats.State s = freshState();
        Map<String, Long> h = history();
        s.day = "2026-10-27";
        s.accMs = 30L * MIN;

        DailyWatchStats.rollDayIfNeeded(s, 5_000L, wall(2026, 10, 31, 9, 0), h);

        assertEquals(Long.valueOf(30L * MIN), h.get("2026-10-27"));
        assertEquals("中间几天没有记录", null, h.get("2026-10-30"));
    }

    // ---------- 历史淘汰 ----------

    @Test
    public void pruneDropsEntriesOlderThan365Days() {
        Map<String, Long> h = history();
        h.put("2025-10-30", 1L);   // 366 天前 -> 淘汰
        h.put("2025-10-31", 2L);   // 365 天前 -> 保留
        h.put("2026-10-31", 3L);

        DailyWatchStats.pruneHistory(h, "2026-10-31");

        assertEquals(null, h.get("2025-10-30"));
        assertEquals(Long.valueOf(2L), h.get("2025-10-31"));
        assertEquals(Long.valueOf(3L), h.get("2026-10-31"));
    }

    // ---------- 工具函数 ----------

    @Test
    public void dayKeyAndPreviousDay() {
        assertEquals("2026-10-31", DailyWatchStats.dayKey(wall(2026, 10, 31, 23, 59)));
        assertEquals("2026-10-30", DailyWatchStats.previousDayKey("2026-10-31"));
        assertEquals("2026-02-28", DailyWatchStats.previousDayKey("2026-03-01"));
        assertEquals("2026-01-01", DailyWatchStats.previousDayKey("2026-01-02"));
    }

    @Test
    public void midnightIsStartOfSameDay() {
        long midnight = DailyWatchStats.midnightWall(wall(2026, 10, 31, 15, 20));
        assertEquals(wall(2026, 10, 31, 0, 0), midnight);
    }

    @Test
    public void clockJumpBackwardsNeverYieldsNegativeCarry() {
        DailyWatchStats.State s = freshState();
        Map<String, Long> h = history();
        s.day = "2026-10-30";
        s.accMs = 10L * MIN;
        s.onSinceElapsed = 5_000L;
        // 墙钟被往回调：段起点晚于「现在」
        s.onSinceWall = wall(2026, 10, 31, 0, 20);

        DailyWatchStats.Roll r = DailyWatchStats.rollDayIfNeeded(
                s, 6_000L, wall(2026, 10, 31, 0, 10), h);

        assertTrue("上一日不能出现负数", r.prevTotal >= 10L * MIN);
        assertTrue("今日不能出现负数", s.accMs >= 0L);
    }
}
