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

import org.junit.Test;

/** 时长格式化的边界值：设置页与全屏 mask 共用，两处显示必须一致。 */
public class DurationFormatTest {

    @Test
    public void zeroAndNegative() {
        assertEquals("0 分钟", DurationFormat.formatDuration(0L));
        assertEquals("0 分钟", DurationFormat.formatDuration(-5_000L));
    }

    @Test
    public void subMinuteRoundsToZero() {
        assertEquals("0 分钟", DurationFormat.formatDuration(29_000L));
    }

    @Test
    public void minutesOnly() {
        assertEquals("45 分钟", DurationFormat.formatDuration(45L * 60_000L));
        assertEquals("58 分钟", DurationFormat.formatDuration(58L * 60_000L));
    }

    @Test
    public void wholeHoursOmitMinutes() {
        assertEquals("2 小时", DurationFormat.formatDuration(2L * 3_600_000L));
    }

    @Test
    public void hoursAndMinutes() {
        assertEquals("2 小时 15 分", DurationFormat.formatDuration(2L * 3_600_000L + 15L * 60_000L));
        assertEquals("1 小时 12 分", DurationFormat.formatDuration(72L * 60_000L));
    }

    @Test
    public void secondsRoundToNearestMinute() {
        // 1 小时 59 分 40 秒 -> 四舍五入成整点，不能显示成「1 小时 60 分」
        assertEquals("2 小时", DurationFormat.formatDuration(3_600_000L + 59L * 60_000L + 40_000L));
        // 44 分 31 秒 -> 45 分钟
        assertEquals("45 分钟", DurationFormat.formatDuration(44L * 60_000L + 31_000L));
    }
}
