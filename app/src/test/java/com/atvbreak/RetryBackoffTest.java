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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 每日提醒「到点但没能展示」时的退避：必须单调递增并封顶，
 * 否则前台停留或权限缺失会让设备每分钟被唤醒一次、永不停歇。
 */
public class RetryBackoffTest {

    private static final long MIN = 60_000L;

    @Test
    public void firstRetryIsOneMinute() {
        assertEquals(1L * MIN, WatchTimerService.nextRetryDelay(1));
    }

    @Test
    public void delayDoublesEachFailure() {
        assertEquals(2L * MIN, WatchTimerService.nextRetryDelay(2));
        assertEquals(4L * MIN, WatchTimerService.nextRetryDelay(3));
        assertEquals(8L * MIN, WatchTimerService.nextRetryDelay(4));
    }

    @Test
    public void delayIsCappedAtTickInterval() {
        assertEquals(WatchTimerService.DAILY_RETRY_MAX_MS, WatchTimerService.nextRetryDelay(5));
        assertEquals(WatchTimerService.DAILY_RETRY_MAX_MS, WatchTimerService.nextRetryDelay(500));
        assertEquals("上限不能超过看门狗周期，否则状态恢复后迟迟排不上",
                WatchTimerService.TICK_INTERVAL_MS, WatchTimerService.DAILY_RETRY_MAX_MS);
    }

    @Test
    public void delayIsMonotonic() {
        long prev = 0L;
        for (int n = 1; n <= 40; n++) {
            long d = WatchTimerService.nextRetryDelay(n);
            assertTrue("第 " + n + " 次退避不应变小", d >= prev);
            prev = d;
        }
    }
}
