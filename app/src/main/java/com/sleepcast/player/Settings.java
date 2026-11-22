package com.sleepcast.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import java.util.Locale;

/**
 * Current settings: the built-in defaults from res/values/config.xml,
 * overridden by anything changed in the app's Settings section.
 */
public class Settings {
    public static final String PREFS = "sleepcast";
    private static final String K_FEED = "feed_url";
    private static final String K_MIN = "min_episode";
    private static final String K_MAX = "max_episode";
    private static final String K_QUEUE = "queue_size";
    private static final String K_NUMBERING = "numbering";
    public static final String K_ALLOW_MOBILE = "allow_mobile_streaming";

    public static final String[] NUMBERING_MODES = {"auto", "tag", "order"};

    public String feedUrl;
    public int minEpisode;
    public int maxEpisode;      // 0 = no upper limit
    public int queueSize;
    public String numbering;    // auto | tag | order
    public boolean allowMobile;
    public boolean autoStart;
    public int maxCached;
    public int refreshDays;

    public static Settings load(Context ctx) {
        Resources r = ctx.getResources();
        SharedPreferences p = prefs(ctx);
        Settings s = new Settings();
        s.feedUrl = p.getString(K_FEED, r.getString(R.string.default_feed_url)).trim();
        s.minEpisode = p.getInt(K_MIN, r.getInteger(R.integer.default_min_episode));
        s.maxEpisode = p.getInt(K_MAX, r.getInteger(R.integer.default_max_episode));
        s.queueSize = Math.max(1, p.getInt(K_QUEUE, r.getInteger(R.integer.default_queue_size)));
        s.numbering = p.getString(K_NUMBERING, r.getString(R.string.default_numbering))
                .trim().toLowerCase(Locale.US);
        s.allowMobile = p.getBoolean(K_ALLOW_MOBILE, false);
        s.autoStart = r.getBoolean(R.bool.auto_start_on_open);
        s.maxCached = Math.max(1, r.getInteger(R.integer.max_cached_episodes));
        s.refreshDays = Math.max(0, r.getInteger(R.integer.feed_refresh_days));
        return s;
    }

    public void save(Context ctx) {
        prefs(ctx).edit()
                .putString(K_FEED, feedUrl)
                .putInt(K_MIN, minEpisode)
                .putInt(K_MAX, maxEpisode)
                .putInt(K_QUEUE, queueSize)
                .putString(K_NUMBERING, numbering)
                .apply();
    }

    public static void resetToDefaults(Context ctx) {
        prefs(ctx).edit().remove(K_FEED).remove(K_MIN).remove(K_MAX)
                .remove(K_QUEUE).remove(K_NUMBERING).apply();
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean inRange(int n) {
        return n >= minEpisode && (maxEpisode <= 0 || n <= maxEpisode);
    }

    public String rangeLabel() {
        return maxEpisode <= 0 ? "#" + minEpisode + " onward" : "#" + minEpisode + " to #" + maxEpisode;
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
        if (minEpisode < 1) return "\"From episode\" must be 1 or more.";
        if (maxEpisode > 0 && maxEpisode < minEpisode) {
            return "\"To episode\" must be bigger than \"From episode\" (or 0 for no limit).";
        }
        boolean known = false;
        for (String m : NUMBERING_MODES) if (m.equals(numbering)) known = true;
        if (!known) return "Numbering must be auto, tag or order.";
        return null;
    }
}
