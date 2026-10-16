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
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

/**
 * 提醒配置：默认值 + SharedPreferences 持久化 + 外部配置文件导入/导出。
 *
 * 配置文件路径：{@code /sdcard/Android/data/com.atvbreak/files/config.json}
 * （应用专属外部目录，Android 4.4+ 无需任何存储权限即可读写，adb 也能直接改）
 */
public final class AppConfig {

    private static final String TAG = "AtvBreak.Config";

    public static final String DEFAULT_MESSAGE = "你已经连续观看45分钟，建议休息15分钟后再继续看电视哦。";
    public static final int DEFAULT_THRESHOLD_MINUTES = 45;
    public static final int DEFAULT_DISMISS_SECONDS = 10;
    public static final int DEFAULT_RESET_AFTER_SCREEN_OFF_MINUTES = 10;
    public static final int DEFAULT_MARGIN_DP = 40;
    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean DEFAULT_SOUND_ENABLED = true;
    /** 提示音音量通道：notification（通知音量，默认）/ alarm（闹钟音量）。 */
    public static final String SOUND_STREAM_NOTIFICATION = "notification";
    public static final String SOUND_STREAM_ALARM = "alarm";
    public static final String DEFAULT_SOUND_STREAM = SOUND_STREAM_NOTIFICATION;

    static final String PREFS = "atv_break";
    static final String K_MESSAGE = "message";
    static final String K_THRESHOLD_MINUTES = "threshold_minutes";
    static final String K_DISMISS_SECONDS = "dismiss_seconds";
    static final String K_RESET_AFTER_OFF_MINUTES = "reset_after_screen_off_minutes";
    static final String K_MARGIN_DP = "margin_dp";
    static final String K_ENABLED = "enabled";
    static final String K_SOUND_ENABLED = "sound_enabled";
    static final String K_SOUND_STREAM = "sound_stream";
    static final String K_FILE_MTIME_EXT = "config_file_mtime_ext";
    static final String K_FILE_MTIME_INT = "config_file_mtime_int";

    String message = DEFAULT_MESSAGE;
    int thresholdMinutes = DEFAULT_THRESHOLD_MINUTES;
    int dismissSeconds = DEFAULT_DISMISS_SECONDS;
    int resetAfterScreenOffMinutes = DEFAULT_RESET_AFTER_SCREEN_OFF_MINUTES;
    int marginDp = DEFAULT_MARGIN_DP;
    boolean enabled = DEFAULT_ENABLED;
    boolean soundEnabled = DEFAULT_SOUND_ENABLED;
    String soundStream = DEFAULT_SOUND_STREAM;

    public String message() {
        return message;
    }

    public int thresholdMinutes() {
        return thresholdMinutes;
    }

    public int dismissSeconds() {
        return dismissSeconds;
    }

    public int resetAfterScreenOffMinutes() {
        return resetAfterScreenOffMinutes;
    }

    public int marginDp() {
        return marginDp;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean soundEnabled() {
        return soundEnabled;
    }

    public String soundStream() {
        return soundStream;
    }

    /** 提示音是否走闹钟音量通道。 */
    public boolean useAlarmStream() {
        return SOUND_STREAM_ALARM.equals(soundStream);
    }

    public long thresholdMillis() {
        return thresholdMinutes * 60_000L;
    }

    public long dismissMillis() {
        return dismissSeconds * 1_000L;
    }

    public long resetAfterScreenOffMillis() {
        return resetAfterScreenOffMinutes * 60_000L;
    }

    // ---------- 持久化 ----------

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void save(Context c) {
        prefs(c).edit()
                .putString(K_MESSAGE, message)
                .putInt(K_THRESHOLD_MINUTES, thresholdMinutes)
                .putInt(K_DISMISS_SECONDS, dismissSeconds)
                .putInt(K_RESET_AFTER_OFF_MINUTES, resetAfterScreenOffMinutes)
                .putInt(K_MARGIN_DP, marginDp)
                .putBoolean(K_ENABLED, enabled)
                .putBoolean(K_SOUND_ENABLED, soundEnabled)
                .putString(K_SOUND_STREAM, soundStream)
                .apply();
    }

    public static AppConfig load(Context c) {
        AppConfig cfg = fromPrefs(prefs(c));
        return mergeFromFile(c, cfg);
    }

    /** 只从 SharedPreferences 读（不含文件合并），供设置界面回填。 */
    public static AppConfig fromPrefsOnly(Context c) {
        return fromPrefs(prefs(c));
    }

    private static AppConfig fromPrefs(SharedPreferences p) {
        AppConfig cfg = new AppConfig();
        cfg.message = p.getString(K_MESSAGE, DEFAULT_MESSAGE);
        cfg.thresholdMinutes = positiveInt(p, K_THRESHOLD_MINUTES, DEFAULT_THRESHOLD_MINUTES);
        cfg.dismissSeconds = positiveInt(p, K_DISMISS_SECONDS, DEFAULT_DISMISS_SECONDS);
        cfg.resetAfterScreenOffMinutes = p.getInt(K_RESET_AFTER_OFF_MINUTES, DEFAULT_RESET_AFTER_SCREEN_OFF_MINUTES);
        cfg.marginDp = p.getInt(K_MARGIN_DP, DEFAULT_MARGIN_DP);
        cfg.enabled = p.getBoolean(K_ENABLED, DEFAULT_ENABLED);
        cfg.soundEnabled = p.getBoolean(K_SOUND_ENABLED, DEFAULT_SOUND_ENABLED);
        cfg.soundStream = normalizeStream(p.getString(K_SOUND_STREAM, DEFAULT_SOUND_STREAM));
        return cfg;
    }

    /** 音量通道白名单：非 alarm 一律回落到 notification。 */
    static String normalizeStream(String s) {
        return SOUND_STREAM_ALARM.equals(s) ? SOUND_STREAM_ALARM : SOUND_STREAM_NOTIFICATION;
    }

    private static int positiveInt(SharedPreferences p, String key, int def) {
        int v = p.getInt(key, def);
        return v > 0 ? v : def;
    }

    // ---------- 配置文件 ----------

    /**
     * 配置文件候选位置（两处都会读，谁新用谁）：
     * 1) 应用专属外部目录（无需权限，文件管理器可见）；
     * 2) 内部存储 files 目录（Android 11+ 的盒子上 adb 反而不能写外部目录，
     *    可用 `adb shell run-as com.atvbreak sh -c 'cat > files/config.json'` 写入）。
     */
    public static File[] configFileCandidates(Context c) {
        File ext = c.getExternalFilesDir(null);
        if (ext != null && !ext.exists() && !ext.mkdirs()) {
            Log.w(TAG, "无法创建配置目录: " + ext);
        }
        return new File[]{
                ext == null ? null : new File(ext, "config.json"),
                new File(c.getFilesDir(), "config.json")
        };
    }

    /** 对外展示的主路径（外部目录；不可用时退回内部目录）。 */
    public static File configFile(Context c) {
        File[] candidates = configFileCandidates(c);
        if (candidates[0] != null) {
            return candidates[0];
        }
        return candidates[1];
    }

    /** 若任一配置文件比上次应用的版本新，则覆盖本地配置。 */
    private static AppConfig mergeFromFile(Context c, AppConfig current) {
        SharedPreferences p = prefs(c);
        File[] candidates = configFileCandidates(c);
        String[] keys = {K_FILE_MTIME_EXT, K_FILE_MTIME_INT};
        AppConfig result = current;
        for (int i = 0; i < candidates.length; i++) {
            File f = candidates[i];
            if (f == null || !f.exists()) {
                continue;
            }
            long mtime = f.lastModified();
            if (mtime <= p.getLong(keys[i], 0L)) {
                continue;
            }
            AppConfig parsed = parse(readFile(f));
            if (parsed == null) {
                Log.w(TAG, "配置文件解析失败，忽略: " + f);
                continue;
            }
            parsed.save(c);
            p.edit().putLong(keys[i], mtime).apply();
            Log.i(TAG, "已从配置文件加载: " + f);
            result = parsed;
        }
        return result;
    }

    /** 强制从文件导入（取两处中最新的那个）。返回 null 表示都不存在或解析失败。 */
    public static AppConfig importFromFile(Context c) {
        File[] candidates = configFileCandidates(c);
        String[] keys = {K_FILE_MTIME_EXT, K_FILE_MTIME_INT};
        File best = null;
        String bestKey = null;
        for (int i = 0; i < candidates.length; i++) {
            File f = candidates[i];
            if (f == null || !f.exists()) {
                continue;
            }
            if (best == null || f.lastModified() > best.lastModified()) {
                best = f;
                bestKey = keys[i];
            }
        }
        if (best == null) {
            return null;
        }
        AppConfig parsed = parse(readFile(best));
        if (parsed == null) {
            return null;
        }
        parsed.save(c);
        prefs(c).edit().putLong(bestKey, best.lastModified()).apply();
        return parsed;
    }

    /** 把当前配置写到两处候选位置，便于编辑后导回。 */
    public static File exportToFile(Context c, AppConfig cfg) {
        File[] candidates = configFileCandidates(c);
        String[] keys = {K_FILE_MTIME_EXT, K_FILE_MTIME_INT};
        File written = null;
        for (int i = 0; i < candidates.length; i++) {
            File f = candidates[i];
            if (f == null) {
                continue;
            }
            try {
                JSONObject o = toJson(cfg);
                try (FileOutputStream out = new FileOutputStream(f, false)) {
                    out.write(o.toString(2).getBytes(Charset.forName("UTF-8")));
                }
                prefs(c).edit().putLong(keys[i], f.lastModified()).apply();
                written = written == null ? f : written;
            } catch (Exception e) {
                Log.e(TAG, "导出配置失败: " + f, e);
            }
        }
        return written;
    }

    private static JSONObject toJson(AppConfig cfg) throws Exception {
        JSONObject o = new JSONObject();
        o.put("message", cfg.message);
        o.put("thresholdMinutes", cfg.thresholdMinutes);
        o.put("dismissSeconds", cfg.dismissSeconds);
        o.put("resetAfterScreenOffMinutes", cfg.resetAfterScreenOffMinutes);
        o.put("marginDp", cfg.marginDp);
        o.put("enabled", cfg.enabled);
        o.put("soundEnabled", cfg.soundEnabled);
        o.put("soundStream", cfg.soundStream);
        return o;
    }

    private static AppConfig parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            AppConfig cfg = new AppConfig();
            if (o.has("message")) {
                cfg.message = o.optString("message", DEFAULT_MESSAGE);
            }
            if (o.has("thresholdMinutes")) {
                cfg.thresholdMinutes = o.getInt("thresholdMinutes");
            }
            if (o.has("dismissSeconds")) {
                cfg.dismissSeconds = o.getInt("dismissSeconds");
            }
            if (o.has("resetAfterScreenOffMinutes")) {
                cfg.resetAfterScreenOffMinutes = o.getInt("resetAfterScreenOffMinutes");
            }
            if (o.has("marginDp")) {
                cfg.marginDp = o.getInt("marginDp");
            }
            if (o.has("enabled")) {
                cfg.enabled = o.getBoolean("enabled");
            }
            if (o.has("soundEnabled")) {
                cfg.soundEnabled = o.getBoolean("soundEnabled");
            }
            if (o.has("soundStream")) {
                cfg.soundStream = normalizeStream(o.optString("soundStream", DEFAULT_SOUND_STREAM));
            }
            if (cfg.thresholdMinutes <= 0) {
                cfg.thresholdMinutes = DEFAULT_THRESHOLD_MINUTES;
            }
            if (cfg.dismissSeconds <= 0) {
                cfg.dismissSeconds = DEFAULT_DISMISS_SECONDS;
            }
            return cfg;
        } catch (Exception e) {
            Log.e(TAG, "解析配置 JSON 失败", e);
            return null;
        }
    }

    private static String readFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), Charset.forName("UTF-8"));
        } catch (Exception e) {
            Log.e(TAG, "读取配置文件失败", e);
            return null;
        }
    }
}
