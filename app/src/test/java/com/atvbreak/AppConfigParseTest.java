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

/** 配置解析：缺字段用默认、非法值回落、越界钳制到范围内。 */
public class AppConfigParseTest {

    private static AppConfig parse(String json) {
        AppConfig c = AppConfig.parseForTest(json);
        if (c == null) {
            throw new AssertionError("解析不应返回 null: " + json);
        }
        return c;
    }

    @Test
    public void missingFieldsUseDefaults() {
        AppConfig c = parse("{}");
        assertTrue(c.dailyEnabled());
        assertEquals(120, c.dailyThresholdMinutes());
        assertEquals(60, c.dailyMaskSeconds());
        assertEquals(30, c.dailyRepeatMinutes());
        assertEquals(AppConfig.DEFAULT_DAILY_TITLE, c.dailyTitle());
        assertEquals(AppConfig.DEFAULT_DAILY_MESSAGE, c.dailyMessage());
    }

    @Test
    public void dailyFieldsRoundTrip() {
        AppConfig c = parse("{\"dailyEnabled\":false,\"dailyThresholdMinutes\":90,"
                + "\"dailyMaskSeconds\":45,\"dailyRepeatMinutes\":0,"
                + "\"dailyTitle\":\"看了 {duration}\",\"dailyMessage\":\"休息\"}");
        assertFalse(c.dailyEnabled());
        assertEquals(90, c.dailyThresholdMinutes());
        assertEquals(45, c.dailyMaskSeconds());
        assertEquals(0, c.dailyRepeatMinutes());
        assertEquals("看了 {duration}", c.dailyTitle());
        assertEquals("休息", c.dailyMessage());
    }

    @Test
    public void dailyThresholdClampedTo10_1440() {
        assertEquals(10, parse("{\"dailyThresholdMinutes\":1}").dailyThresholdMinutes());
        assertEquals(1440, parse("{\"dailyThresholdMinutes\":99999}").dailyThresholdMinutes());
        assertEquals(10, parse("{\"dailyThresholdMinutes\":0}").dailyThresholdMinutes());
        assertEquals(10, parse("{\"dailyThresholdMinutes\":-5}").dailyThresholdMinutes());
    }

    @Test
    public void dailyMaskSecondsClampedTo10_180() {
        assertEquals(10, parse("{\"dailyMaskSeconds\":1}").dailyMaskSeconds());
        assertEquals(180, parse("{\"dailyMaskSeconds\":600}").dailyMaskSeconds());
        assertEquals(10, parse("{\"dailyMaskSeconds\":0}").dailyMaskSeconds());
    }

    @Test
    public void dailyRepeatMinutesClampedTo0_240() {
        assertEquals(0, parse("{\"dailyRepeatMinutes\":0}").dailyRepeatMinutes());
        assertEquals(240, parse("{\"dailyRepeatMinutes\":999}").dailyRepeatMinutes());
        assertEquals(0, parse("{\"dailyRepeatMinutes\":-30}").dailyRepeatMinutes());
    }

    @Test
    public void blankDailyTitleFallsBackToDefault() {
        assertEquals(AppConfig.DEFAULT_DAILY_TITLE, parse("{\"dailyTitle\":\"  \"}").dailyTitle());
        assertEquals(AppConfig.DEFAULT_DAILY_MESSAGE, parse("{\"dailyMessage\":\"\"}").dailyMessage());
    }

    @Test
    public void existingFieldsKeepTheirFallbackBehaviour() {
        // 现有字段沿用「非正数回落默认值」，不能被新字段的钳制策略改动
        assertEquals(45, parse("{\"thresholdMinutes\":0}").thresholdMinutes());
        assertEquals(10, parse("{\"dismissSeconds\":0}").dismissSeconds());
    }

    @Test
    public void jsonRoundTripKeepsDailyFields() throws Exception {
        AppConfig c = parse("{\"dailyEnabled\":false,\"dailyThresholdMinutes\":90,"
                + "\"dailyMaskSeconds\":45,\"dailyRepeatMinutes\":15,"
                + "\"dailyTitle\":\"T\",\"dailyMessage\":\"M\"}");
        AppConfig back = AppConfig.parseForTest(AppConfig.toJsonForTest(c).toString());
        assertFalse(back.dailyEnabled());
        assertEquals(90, back.dailyThresholdMinutes());
        assertEquals(45, back.dailyMaskSeconds());
        assertEquals(15, back.dailyRepeatMinutes());
        assertEquals("T", back.dailyTitle());
        assertEquals("M", back.dailyMessage());
    }
}
