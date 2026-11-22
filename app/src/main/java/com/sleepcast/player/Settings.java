package com.sleepcast.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import java.util.Locale;

/**
 * Current settings: the built-in defaults from res/values/config.xml,
 * overridden by anything changed on the app's Settings page.
 */
public class Settings {
    public static final String PREFS = "sleepcast";

    // Podcast
    static final String K_FEED = "feed_url";
    static final String K_MIN = "min_episode";
    static final String K_MAX = "max_episode";
    static final String K_NUMBERING = "numbering";
    // Playback
    static final String K_QUEUE = "queue_size";
    static final String K_AUTO_START = "auto_start";
    static final String K_KEEP_GOING = "keep_going";
    static final String K_SLEEP_TIMER = "sleep_timer_min";
    static final String K_SPEED = "speed";
    static final String K_PAUSE_UNPLUG = "pause_on_unplug";
    // Downloads
    static final String K_DOWNLOAD_AHEAD = "download_ahead";
    public static final String K_ALLOW_MOBILE = "allow_mobile_streaming";
    static final String K_MAX_CACHED = "max_cached";
    static final String K_REFRESH_DAYS = "refresh_days";
    // Remembered from the last successful feed load (for display only)
    static final String K_LAST_SHOW = "last_show_title";

    public static final String[] NUMBERING_MODES = {"auto", "tag", "order"};

    public String feedUrl;
    public int minEpisode;
    public int maxEpisode;      // 0 = no upper limit
    public String numbering;    // auto | tag | order

    public int queueSize;
    public boolean autoStart;
    public boolean keepGoing;   // add another batch when the queue runs out
    public int sleepTimerMin;   // 0 = play until stopped
    public float speed;
    public boolean pauseOnUnplug;

    public boolean downloadAhead;
    public boolean allowMobile;
    public int maxCached;
    public int refreshDays;     // 0 = fetch the feed once

    public static Settings load(Context ctx) {
        Resources r = ctx.getResources();
        SharedPreferences p = prefs(ctx);
        Settings s = new Settings();
        s.feedUrl = p.getString(K_FEED, r.getString(R.string.default_feed_url)).trim();
        s.minEpisode = p.getInt(K_MIN, r.getInteger(R.integer.default_min_episode));
        s.maxEpisode = p.getInt(K_MAX, r.getInteger(R.integer.default_max_episode));
        s.numbering = p.getString(K_NUMBERING, r.getString(R.string.default_numbering))
                .trim().toLowerCase(Locale.US);

        s.queueSize = Math.max(1, p.getInt(K_QUEUE, r.getInteger(R.integer.default_queue_size)));
        s.autoStart = p.getBoolean(K_AUTO_START, r.getBoolean(R.bool.auto_start_on_open));
        s.keepGoing = p.getBoolean(K_KEEP_GOING, true);
        s.sleepTimerMin = Math.max(0, p.getInt(K_SLEEP_TIMER, 0));
        s.speed = p.getFloat(K_SPEED, 1.0f);
        s.pauseOnUnplug = p.getBoolean(K_PAUSE_UNPLUG, true);

        s.downloadAhead = p.getBoolean(K_DOWNLOAD_AHEAD, true);
        s.allowMobile = p.getBoolean(K_ALLOW_MOBILE, false);
        s.maxCached = Math.max(1, p.getInt(K_MAX_CACHED, r.getInteger(R.integer.max_cached_episodes)));
        s.refreshDays = Math.max(0, p.getInt(K_REFRESH_DAYS, r.getInteger(R.integer.feed_refresh_days)));
        return s;
    }

    /** Saves the podcast section (feed, range, numbering). */
    public void savePodcast(Context ctx) {
        prefs(ctx).edit()
                .putString(K_FEED, feedUrl)
                .putInt(K_MIN, minEpisode)
                .putInt(K_MAX, maxEpisode)
                .putString(K_NUMBERING, numbering)
                .apply();
    }

    public static void put(Context ctx, String key, Object value) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) e.putInt(key, (Integer) value);
        else if (value instanceof Float) e.putFloat(key, (Float) value);
        else e.putString(key, String.valueOf(value));
        e.apply();
    }

    /** Clears every in-app change so config.xml's defaults apply again. */
    public static void resetToDefaults(Context ctx) {
        String show = prefs(ctx).getString(K_LAST_SHOW, "");
        prefs(ctx).edit().clear().putString(K_LAST_SHOW, show).apply();
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String lastShowTitle(Context ctx) {
        return prefs(ctx).getString(K_LAST_SHOW, "");
    }

    public boolean inRange(int n) {
        return n >= minEpisode && (maxEpisode <= 0 || n <= maxEpisode);
    }

    public String rangeLabel() {
        return maxEpisode <= 0 ? "#" + minEpisode + " onward" : "#" + minEpisode + " – #" + maxEpisode;
    }

    /** Short stable id for this feed + numbering, used to keep caches for different feeds apart. */
    public String feedKey() {
        return Integer.toHexString((feedUrl + "|" + numbering).hashCode());
    }

    /** Returns an error message, or null if the settings are usable. */
    public String validate() {
        if (feedUrl.isEmpty()) return "Add a podcast RSS feed URL in Settings.";
        if (!feedUrl.startsWith("http://") && !feedUrl.startsWith("https://")) {
            return "The feed URL should start with https://";
        }
        if (minEpisode < 1) return "\"From\" episode must be 1 or more.";
        if (maxEpisode > 0 && maxEpisode < minEpisode) {
            return "\"To\" episode must be bigger than \"From\" (or 0 for no limit).";
        }
        boolean known = false;
        for (String m : NUMBERING_MODES) if (m.equals(numbering)) known = true;
        if (!known) return "Numbering must be auto, tag or order.";
        return null;
    }

    // ---- labels shared by the screens ----

    public static String numberingLabel(String mode) {
        switch (mode) {
            case "tag": return "Feed's own episode numbers";
            case "order": return "Release order (oldest = #1)";
            default: return "Automatic";
        }
    }

    public static String timerLabel(int min) {
        if (min <= 0) return "Off · play until stopped";
        if (min < 60) return min + " minutes";
        return (min / 60) + (min % 60 == 0 ? "" : "." + (min % 60 * 10 / 60))
                + (min == 60 ? " hour" : " hours");
    }

    public static String speedLabel(float speed) {
        if (Math.abs(speed - 1f) < 0.01f) return "Normal (1×)";
        return String.format(Locale.US, "%s×", trim(speed));
    }

    public static String refreshLabel(int days) {
        if (days <= 0) return "Never (fetch once)";
        if (days == 1) return "Every day";
        if (days == 7) return "Every week";
        if (days == 30) return "Every month";
        return "Every " + days + " days";
    }

    private static String trim(float f) {
        String s = String.format(Locale.US, "%.2f", f);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
