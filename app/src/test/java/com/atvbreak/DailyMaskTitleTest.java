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

/** mask 标题模板渲染：{duration} 占位符与空模板回落。 */
public class DailyMaskTitleTest {

    @Test
    public void replacesPlaceholder() {
        assertEquals("今天已经看电视 2 小时 15 分啦",
                DurationFormat.renderTitleTemplate("今天已经看电视 {duration}啦", "2 小时 15 分"));
    }

    @Test
    public void multiplePlaceholders() {
        assertEquals("看了 45 分钟，再看 45 分钟就该休息",
                DurationFormat.renderTitleTemplate("看了 {duration}，再看 {duration}就该休息", "45 分钟"));
    }

    @Test
    public void templateWithoutPlaceholderIsKept() {
        assertEquals("该休息啦", DurationFormat.renderTitleTemplate("该休息啦", "2 小时"));
    }

    @Test
    public void blankTemplateFallsBackToDefault() {
        assertEquals("今天已经看电视 2 小时啦",
                DurationFormat.renderTitleTemplate("   ", "2 小时"));
        assertEquals("今天已经看电视 2 小时啦",
                DurationFormat.renderTitleTemplate(null, "2 小时"));
    }
}
