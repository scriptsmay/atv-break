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
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 每日累计超限的全屏提醒 mask。
 *
 * 设计要点：
 * - {@code TYPE_APPLICATION_OVERLAY} 全屏悬浮窗，<b>不抢焦点、不吃触摸</b>，不打断底层播放；
 * - 固定时长后自动关闭（默认 60 秒），无需遥控器交互；
 * - 铺满包含状态栏区域，避免部分盒子留下一条未被遮住的边；
 * - 与连续提醒的小浮层互斥：mask 显示期间抑制小浮层与第二路提示音。
 *
 * 已知取舍：非焦点窗口不接收按键，用户切台 / 换应用 / 按主页键即可绕过，
 * mask 只是视觉阻断，不构成强制拦截。
 */
public final class DailyMaskOverlay {

    private static final String TAG = "AtvBreak.DailyMask";

    private static View overlayView;
    private static WindowManager windowManager;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static long dismissTotalMs = 60_000L;
    private static long dismissStartMs;
    private static boolean showing;

    private static final Runnable dismissTask = DailyMaskOverlay::dismissInternal;
    private static final Runnable countdownTask = new Runnable() {
        @Override
        public void run() {
            tickCountdown();
        }
    };

    private DailyMaskOverlay() {
    }

    /** mask 是否正在显示（供连续提醒仲裁）。 */
    public static synchronized boolean isShowing() {
        return showing;
    }

    /**
     * 展示全屏 mask。
     *
     * @param todayTotalMs 今日累计毫秒，用于渲染标题里的 {duration}
     * @return true 表示确实展示成功；未授予悬浮窗权限或添加失败时返回 false，
     *         调用方据此决定是否写「已提醒」标记——失败时不得写，否则当天再也收不到提醒。
     */
    public static synchronized boolean show(Context c, AppConfig cfg, long todayTotalMs) {
        Context ctx = c.getApplicationContext();
        handler.removeCallbacks(dismissTask);
        handler.removeCallbacks(countdownTask);

        if (!ReminderOverlay.canDrawOverlays(ctx)) {
            Log.w(TAG, "未授予悬浮窗权限，无法展示全屏提醒");
            return false;
        }

        // mask 优先：先收起连续提醒的小浮层，避免叠层与双提示音
        ReminderOverlay.dismiss(ctx);

        if (overlayView == null) {
            overlayView = LayoutInflater.from(ctx).inflate(R.layout.overlay_daily_mask, null);
            windowManager = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        }
        if (overlayView.getParent() != null) {
            try {
                windowManager.removeViewImmediate(overlayView);
            } catch (Exception e) {
                Log.w(TAG, "移除旧 mask 失败", e);
            }
        }

        String duration = DurationFormat.formatDuration(todayTotalMs);
        TextView title = overlayView.findViewById(R.id.daily_mask_title);
        TextView message = overlayView.findViewById(R.id.daily_mask_message);
        title.setText(renderTitle(cfg.dailyTitle(), duration));
        message.setText(cfg.dailyMessage());
        applySafeMargins(ctx);

        try {
            windowManager.addView(overlayView, buildLayoutParams(ctx));
        } catch (Exception e) {
            Log.e(TAG, "添加全屏提醒失败", e);
            return false;
        }
        showing = true;

        if (cfg.soundEnabled()) {
            ReminderSound.play(ctx, cfg.useAlarmStream());
        }

        // 入场：300ms alpha 0→1 + scale 0.96→1.0
        AnimationSet set = new AnimationSet(true);
        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
        fade.setDuration(300);
        ScaleAnimation scale = new ScaleAnimation(0.96f, 1f, 0.96f, 1f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
        scale.setDuration(300);
        set.addAnimation(fade);
        set.addAnimation(scale);
        overlayView.startAnimation(set);

        dismissTotalMs = cfg.dailyMaskMillis();
        if (dismissTotalMs <= 0L) {
            dismissTotalMs = 60_000L;
        }
        dismissStartMs = System.currentTimeMillis();
        tickCountdown();
        handler.postDelayed(dismissTask, dismissTotalMs);
        return true;
    }

    /**
     * 5% overscan 安全边距：电视普遍存在过扫描，底部出口提示很容易被裁掉。
     * 用实际窗口尺寸算像素，比写死 dp 更能适配不同盒子。
     */
    private static void applySafeMargins(Context ctx) {
        View safe = overlayView.findViewById(R.id.daily_mask_safe);
        View footer = overlayView.findViewById(R.id.daily_mask_footer);
        int w = ctx.getResources().getDisplayMetrics().widthPixels;
        int h = ctx.getResources().getDisplayMetrics().heightPixels;
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
            w = b.width();
            h = b.height();
        }
        int hx = Math.round(w * 0.05f);
        int hy = Math.round(h * 0.05f);
        if (safe != null && safe.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams lp =
                    (android.view.ViewGroup.MarginLayoutParams) safe.getLayoutParams();
            lp.setMargins(hx, hy, hx, hy);
            safe.setLayoutParams(lp);
        }
        if (footer != null && footer.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams lp =
                    (android.view.ViewGroup.MarginLayoutParams) footer.getLayoutParams();
            lp.setMargins(hx, 0, hx, Math.max(hy, Math.round(h * 0.06f)));
            footer.setLayoutParams(lp);
        }
    }

    /** 标题模板里的 {duration} 渲染为「2 小时」/「2 小时 15 分」/「45 分钟」。 */
    static String renderTitle(String template, String duration) {
        return DurationFormat.renderTitleTemplate(template, duration);
    }

    /** 每 100ms 刷新倒计时读数与进度条。 */
    private static void tickCountdown() {
        if (overlayView == null) {
            return;
        }
        long remain = Math.max(0L, dismissTotalMs - (System.currentTimeMillis() - dismissStartMs));
        ProgressBar bar = overlayView.findViewById(R.id.daily_mask_progress);
        TextView tv = overlayView.findViewById(R.id.daily_mask_countdown);
        if (bar != null) {
            bar.setProgress((int) (1000L * remain / Math.max(1L, dismissTotalMs)));
        }
        if (tv != null) {
            tv.setText(overlayView.getContext().getString(R.string.daily_mask_countdown,
                    (int) ((remain + 999L) / 1000L)));
        }
        if (remain > 0L) {
            handler.postDelayed(countdownTask, 100L);
        }
    }

    public static synchronized void dismiss(Context c) {
        handler.removeCallbacks(dismissTask);
        handler.removeCallbacks(countdownTask);
        dismissInternal();
    }

    private static void dismissInternal() {
        showing = false;
        if (overlayView == null || windowManager == null) {
            return;
        }
        try {
            if (overlayView.getParent() != null) {
                windowManager.removeViewImmediate(overlayView);
            }
        } catch (Exception e) {
            Log.w(TAG, "移除全屏提醒失败", e);
        }
    }

    private static WindowManager.LayoutParams buildLayoutParams(Context ctx) {
        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        // 只写 NOT_FOCUSABLE | NOT_TOUCHABLE 不够：部分盒子会留出状态栏 / 导航栏区域，
        // 全屏遮罩会出现一条未被遮住的边。
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        // API 30+：不因系统栏 / 刘海区收缩布局
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.setFitInsetsTypes(0);
        }
        lp.setTitle(ctx.getString(R.string.app_name));
        return lp;
    }
}
