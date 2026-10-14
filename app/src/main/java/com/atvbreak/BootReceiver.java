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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机 / 解锁 / 应用升级后拉起计时服务，保证「自开机累计」的口径。
 *
 * 同时监听多种开机信号，覆盖不同盒子的行为差异：
 * - BOOT_COMPLETED：标准开机完成（最常见）
 * - LOCKED_BOOT_COMPLETED：未解锁状态下也会发（Android 7+）
 * - USER_UNLOCKED：用户解锁后（部分 ROM 只发这个）
 * - QUICKBOOT_POWERON：部分 Amlogic / MTK 盒子的「快速开机」
 * - MY_PACKAGE_REPLACED：应用升级后自动恢复计时
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "AtvBreak.Boot";

    /** 部分盒子的「快速开机」广播（非标准，但无害）。 */
    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Log.i(TAG, "收到 " + action + "，启动计时服务");
            WatchTimerService.markBoot(context);
            WatchTimerService.reconcile(context);
            WatchTimerService.start(context);
            WatchTimerService.reschedule(context);
        }
    }
}
