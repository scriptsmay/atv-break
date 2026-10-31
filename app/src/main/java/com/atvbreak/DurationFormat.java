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

import java.util.Locale;

/**
 * 时长格式化（设置页与全屏 mask 共用，避免两处各写一份出现不一致）。
 *
 * 输出风格与界面其余文案一致：{@code 45 分钟} / {@code 2 小时} / {@code 2 小时 15 分}。
 * 不足一分钟按四舍五入折算到分钟，整小时不显示「0 分」。
 */
public final class DurationFormat {

    private DurationFormat() {
    }

    public static String formatDuration(long ms) {
        if (ms <= 0L) {
            return "0 分钟";
        }
        long minutes = (ms + 30_000L) / 60_000L;
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        if (hours <= 0L) {
            return String.format(Locale.CHINA, "%d 分钟", rest);
        }
        if (rest == 0L) {
            return String.format(Locale.CHINA, "%d 小时", hours);
        }
        return String.format(Locale.CHINA, "%d 小时 %d 分", hours, rest);
    }

    /**
     * 渲染标题模板：把 {@code {duration}} 替换为格式化后的时长。
     * 模板为空（或全空白）时回落到默认标题。
     */
    public static String renderTitleTemplate(String template, String duration) {
        String t = template == null || template.trim().isEmpty()
                ? AppConfig.DEFAULT_DAILY_TITLE : template;
        return t.replace("{duration}", duration == null ? "" : duration);
    }
}
