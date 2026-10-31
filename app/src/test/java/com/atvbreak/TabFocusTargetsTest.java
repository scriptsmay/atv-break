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
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * tab 栏「下键」目标。
 *
 * 焦点可以停在非当前页的 tab 上（左右移动即可），但下方只渲染当前页；
 * 若「下键」指向该 tab 自己的页面，就会跳进 GONE 页面里的控件——
 * 屏幕上没有任何高亮，用户以为遥控器坏了。
 */
public class TabFocusTargetsTest {

    @Test
    public void everyTabPointsIntoTheCurrentPage() {
        int[] overview = MainActivity.tabDownTargets(MainActivity.TAB_OVERVIEW);
        int[] continuous = MainActivity.tabDownTargets(MainActivity.TAB_CONTINUOUS);
        int[] daily = MainActivity.tabDownTargets(MainActivity.TAB_DAILY);

        for (int i = 0; i < overview.length; i++) {
            assertEquals("总览页：第 " + i + " 个 tab 的下键应进总览首项",
                    R.id.cb_enabled, overview[i]);
            assertEquals("连续提醒页：第 " + i + " 个 tab 的下键应进该页首项",
                    R.id.et_message, continuous[i]);
            assertEquals("每日累计页：第 " + i + " 个 tab 的下键应进该页首项",
                    R.id.cb_daily_enabled, daily[i]);
        }
    }

    @Test
    public void nonActiveTabsDoNotPointAtTheirOwnPage() {
        // 当前页是「总览」时，停在「连续提醒」tab 上按下，不能跳进隐藏的 et_message
        int[] targets = MainActivity.tabDownTargets(MainActivity.TAB_OVERVIEW);
        assertNotEquals(R.id.et_message, targets[MainActivity.TAB_CONTINUOUS]);
        assertNotEquals(R.id.cb_daily_enabled, targets[MainActivity.TAB_DAILY]);
    }

    @Test
    public void switchingPagesChangesEveryTabTarget() {
        int[] overview = MainActivity.tabDownTargets(MainActivity.TAB_OVERVIEW);
        int[] daily = MainActivity.tabDownTargets(MainActivity.TAB_DAILY);
        for (int i = 0; i < overview.length; i++) {
            assertNotEquals("换页后下键目标必须跟着换", overview[i], daily[i]);
        }
    }
}
