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
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.widget.TextView;

/**
 * 右下角提醒浮层。
 *
 * - 使用 SYSTEM_ALERT_WINDOW 悬浮窗，浮在所有应用之上；
 * - FLAG_NOT_FOCUSABLE / FLAG_NOT_TOUCHABLE：不抢焦点、不吃按键，遥控器操作不受影响；
 * - 显示固定时长后由 Handler 自动移除，无需任何交互。
 */
public final class ReminderOverlay {

    private static final String TAG = "AtvBreak.Overlay";

    private static View overlayView;
    private static WindowManager windowManager;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable dismissTask = ReminderOverlay::dismissInternal;

    private ReminderOverlay() {
    }

    public static boolean canDrawOverlays(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(c);
    }

    public static synchronized void show(Context c, String message, long dismissMs, int marginDp) {
        Context ctx = c.getApplicationContext();
        handler.removeCallbacks(dismissTask);

        if (!canDrawOverlays(ctx)) {
            Log.w(TAG, "未授予悬浮窗权限，无法展示提醒浮层");
            return;
        }

        if (overlayView == null) {
            overlayView = LayoutInflater.from(ctx).inflate(R.layout.overlay_reminder, null);
            windowManager = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        }
        if (overlayView.getParent() != null) {
            windowManager.removeViewImmediate(overlayView);
        }

        TextView tv = overlayView.findViewById(R.id.overlay_text);
        tv.setText(message);

        WindowManager.LayoutParams lp = buildLayoutParams(ctx, marginDp);
        try {
            windowManager.addView(overlayView, lp);
        } catch (Exception e) {
            Log.e(TAG, "添加浮层失败", e);
            return;
        }

        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
        fade.setDuration(220);
        overlayView.startAnimation(fade);

        long delay = dismissMs > 0 ? dismissMs : 10_000L;
        handler.postDelayed(dismissTask, delay);
    }

    public static synchronized void dismiss(Context c) {
        handler.removeCallbacks(dismissTask);
        dismissInternal();
    }

    private static void dismissInternal() {
        if (overlayView == null || windowManager == null) {
            return;
        }
        try {
            if (overlayView.getParent() != null) {
                windowManager.removeViewImmediate(overlayView);
            }
        } catch (Exception e) {
            Log.w(TAG, "移除浮层失败", e);
        }
    }

    private static WindowManager.LayoutParams buildLayoutParams(Context ctx, int marginDp) {
        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT);

        lp.gravity = Gravity.BOTTOM | Gravity.END;

        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect bounds = wm.getCurrentWindowMetrics().getBounds();
            screenW = bounds.width();
        }

        int margin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, marginDp,
                ctx.getResources().getDisplayMetrics());
        lp.x = margin;
        lp.y = margin;
        // 宽度上限：最多占屏幕 42%，避免长文案铺满整屏
        lp.width = Math.min(screenW - margin * 2, (int) (screenW * 0.42f));
        lp.windowAnimations = android.R.style.Animation_Toast;
        lp.setTitle(ctx.getString(R.string.app_name));
        return lp;
    }
}
