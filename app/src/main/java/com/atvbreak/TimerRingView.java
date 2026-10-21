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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * 设置页状态卡的「连续观看时长」环形进度。
 *
 * 纯自绘（无第三方依赖）：外圈轨道 + 主色进度弧 + 圆心数字（已看分钟 / 阈值分钟）。
 */
public class TimerRingView extends View {

    private static final float START_ANGLE = -90f;
    private static final float STROKE_DP = 9f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private float progress;
    private String value = "0";
    private String sub = "";

    public TimerRingView(Context context) {
        super(context);
        init();
    }

    public TimerRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TimerRingView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float stroke = dp(STROKE_DP);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(stroke);
        trackPaint.setColor(getResources().getColor(R.color.line));

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(stroke);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(getResources().getColor(R.color.accent));

        valuePaint.setColor(getResources().getColor(R.color.text_primary));
        valuePaint.setTextSize(sp(30));
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setFakeBoldText(true);

        subPaint.setColor(getResources().getColor(R.color.text_secondary));
        subPaint.setTextSize(sp(12));
        subPaint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * @param watchedMs 已连续观看时长
     * @param totalMs   触发阈值（0 或负数时按 100% 处理）
     */
    public void setProgress(long watchedMs, long totalMs) {
        if (totalMs <= 0) {
            progress = 1f;
        } else {
            progress = Math.min(1f, Math.max(0f, watchedMs / (float) totalMs));
        }
        long watchedMin = watchedMs / 60000L;
        long totalMin = Math.max(0L, totalMs / 60000L);
        value = String.valueOf(watchedMin);
        sub = "分钟 / " + totalMin;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float stroke = dp(STROKE_DP);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - stroke / 2f - dp(2);
        if (radius <= 0) {
            return;
        }
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius);

        canvas.drawCircle(cx, cy, radius, trackPaint);
        if (progress > 0f) {
            canvas.drawArc(oval, START_ANGLE, 360f * progress, false, progressPaint);
        }

        Paint.FontMetrics fm = valuePaint.getFontMetrics();
        float valueBase = cy - (fm.ascent + fm.descent) / 2f - dp(6);
        canvas.drawText(value, cx, valueBase, valuePaint);
        canvas.drawText(sub, cx, valueBase - fm.ascent + dp(2), subPaint);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }
}
