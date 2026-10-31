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
 * 指令入口（导出）：既承接系统定时器到点 / 看门狗的回调，也用于 adb 调试。
 *
 * 走广播而不是 Service，好处是：即便前台服务被系统清理，提醒依然能弹、闹钟依然能续、
 * 服务还能被看门狗重新拉起——整套提醒逻辑不依赖常驻进程。
 *
 * adb 用法：
 *   adb shell am broadcast -a com.atvbreak.action.TEST_REMINDER -n com.atvbreak/.ActionReceiver
 *   adb shell am broadcast -a com.atvbreak.action.RESET_TIMER    -n com.atvbreak/.ActionReceiver
 *   adb shell am broadcast -a com.atvbreak.action.RELOAD_CONFIG  -n com.atvbreak/.ActionReceiver
 *   adb shell am broadcast -a com.atvbreak.action.TICK           -n com.atvbreak/.ActionReceiver
 *   adb shell am broadcast -a com.atvbreak.action.TEST_DAILY_MASK -n com.atvbreak/.ActionReceiver
 *   adb shell am broadcast -a com.atvbreak.action.DAILY_ROLL_DAY --es day 2026-10-30 \
 *       -n com.atvbreak/.ActionReceiver
 */
public class ActionReceiver extends BroadcastReceiver {

    private static final String TAG = "AtvBreak.Action";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        Log.i(TAG, "收到指令：" + action);

        switch (action) {
            case WatchTimerService.ACTION_REMIND:
                // 系统提醒闹钟到点
                WatchTimerService.handleRemind(context);
                break;
            case WatchTimerService.ACTION_TICK:
                // 看门狗：对账 + 补设闹钟 + 必要时拉起服务
                WatchTimerService.handleTick(context);
                break;
            case WatchTimerService.ACTION_TEST:
                AppConfig cfg = AppConfig.load(context);
                ReminderOverlay.show(context, cfg);
                WatchTimerService.scheduleTick(context);
                break;
            case WatchTimerService.ACTION_RESET:
                WatchTimerService.resetPersisted(context);
                WatchTimerService.reschedule(context);
                WatchTimerService.send(context, WatchTimerService.ACTION_RESET);
                break;
            case WatchTimerService.ACTION_RELOAD:
                WatchTimerService.reschedule(context);
                WatchTimerService.scheduleDaily(context);
                WatchTimerService.send(context, WatchTimerService.ACTION_RELOAD);
                break;
            case WatchTimerService.ACTION_DAILY_REMIND:
                // 每日累计闹钟到点
                WatchTimerService.handleDailyRemind(context);
                break;
            case WatchTimerService.ACTION_TEST_DAILY_MASK:
                // 验收用：立即弹一次全屏 mask，不写已提醒标记、不改变累计
                AppConfig dailyCfg = AppConfig.load(context);
                DailyMaskOverlay.show(context, dailyCfg, DailyWatchStats.todayTotalMs(context));
                WatchTimerService.scheduleTick(context);
                break;
            case WatchTimerService.ACTION_DAILY_ROLL_DAY: {
                // 调试用：--es day yyyy-MM-dd 注入「当前墙钟」，验证跨天切分
                String day = intent.getStringExtra("day");
                java.text.SimpleDateFormat f =
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
                try {
                    java.util.Date d = day == null ? new java.util.Date()
                            : f.parse(day + " 12:00");
                    DailyWatchStats.rollDay(context, d.getTime());
                    WatchTimerService.scheduleDaily(context);
                } catch (Exception e) {
                    Log.w(TAG, "跨天调试参数无效：" + day, e);
                }
                break;
            }
            case WatchTimerService.ACTION_START:
                WatchTimerService.start(context);
                break;
            default:
                Log.w(TAG, "未知指令：" + action);
        }
    }
}
