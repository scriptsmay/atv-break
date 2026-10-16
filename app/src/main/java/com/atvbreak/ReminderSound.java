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
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

/**
 * 提醒提示音：播放打包在 {@code res/raw/reminder_chime.ogg} 里的短提示音。
 *
 * - 不依赖盒子的系统通知音设置，保证一定有声音；
 * - 音量通道可选：通知音量（默认，像收到短信）或闹钟音量（更不容易被漏听）；
 * - 只保留一个播放实例：再次播放前先停掉上一段，播放完自动释放。
 */
public final class ReminderSound {

    private static final String TAG = "AtvBreak.Sound";

    private static MediaPlayer player;

    private ReminderSound() {
    }

    /**
     * 播放提示音。
     *
     * @param useAlarmStream true 走闹钟音量，false 走通知音量
     */
    public static synchronized void play(Context c, boolean useAlarmStream) {
        Context ctx = c.getApplicationContext();
        stop();

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(useAlarmStream
                        ? AudioAttributes.USAGE_ALARM
                        : AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        try {
            MediaPlayer mp = MediaPlayer.create(ctx, R.raw.reminder_chime, attrs,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (mp == null) {
                Log.w(TAG, "提示音资源加载失败，本次不发声");
                return;
            }
            mp.setOnCompletionListener(ReminderSound::release);
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "提示音播放出错 what=" + what + " extra=" + extra);
                release(p);
                return true;
            });
            player = mp;
            mp.start();
            Log.i(TAG, "播放提示音（通道：" + (useAlarmStream ? "闹钟" : "通知") + "）");
        } catch (Exception e) {
            Log.w(TAG, "播放提示音失败", e);
        }
    }

    private static synchronized void stop() {
        if (player == null) {
            return;
        }
        try {
            player.stop();
        } catch (Exception ignored) {
            // 已结束或状态不允许，忽略
        }
        release(player);
    }

    private static void release(MediaPlayer mp) {
        if (mp == null) {
            return;
        }
        try {
            mp.release();
        } catch (Exception ignored) {
            // 忽略
        }
        synchronized (ReminderSound.class) {
            if (player == mp) {
                player = null;
            }
        }
    }
}
