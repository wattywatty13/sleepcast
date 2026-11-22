package com.sleepcast.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** The full Settings page: podcast, playback, downloads, episode list and about. */
public class SettingsActivity extends Activity {

    private static final int GOLD = 0xFFE8C887;
    private static final int TEXT = 0xFFF4EEDF;
    private static final int MUTED = 0xFF8A93A6;
    private static final int SOFT = 0xFFC9CFDB;
    private static final int GOOD = 0xFF8FD3A8;
    private static final int WARN = 0xFFE8957A;

    private static final int[] TIMER_MIN = {0, 15, 30, 45, 60, 90, 120, 180, 240, 360, 480};
    private static final float[] SPEEDS = {0.75f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f};
    private static final int[] MAX_SAVED = {5, 10, 20, 30, 50, 100};
    private static final int[] REFRESH_DAYS = {0, 1, 7, 30};

    private LinearLayout content;
    private Settings s;

    // Podcast section
    private EditText feedField, fromField, toField;
    private TextView numberingValue, feedResult, testBtn;
    private String pendingNumbering;

    // Live-updating rows
    private TextView storageValue, refreshResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        content = findViewById(R.id.settings_content);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStorage();
    }

    private void build() {
        content.removeAllViews();
        s = Settings.load(this);
        pendingNumbering = s.numbering;
        buildPodcast();
        buildPlayback();
        buildDownloads();
        buildEpisodeList();
        buildAbout();

        TextView reset = pill("Reset all settings", R.drawable.bg_pill_stop, 0xFFF2B6A3);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        lp.topMargin = dp(28);
        content.addView(reset, lp);
        reset.setOnClickListener(v -> new AlertDialog.Builder(this, dialogTheme())
                .setTitle("Reset all settings?")
                .setMessage("Everything goes back to the app's built-in defaults. "
                        + "Downloaded episodes are kept.")
                .setPositiveButton("Reset", (d, w) -> {
                    Settings.resetToDefaults(this);
                    notifyService();
                    build();
                    toast("Settings reset");
                })
                .setNegativeButton("Cancel", null)
                .show());

        TextView foot = text("Settings save as you change them, except the podcast section, "
                + "which saves when you tap Save.", 12, MUTED);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(12), dp(16), dp(12), 0);
        content.addView(foot);
    }

    // ------------------------------------------------------------------ sections

    private void buildPodcast() {
        LinearLayout card = section("PODCAST");
        LinearLayout box = padded(card);

        box.addView(label("RSS feed URL"));
        feedField = input(s.feedUrl, "https://example.com/feed.xml",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(feedField);
        box.addView(hint("The podcast's public RSS (XML) feed. Search the show on "
                + "podcastindex.org and look for \"RSS\"."));

        LinearLayout range = new LinearLayout(this);
        range.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout fromCol = column(), toCol = column();
        fromCol.addView(label("From episode"));
        fromField = input(String.valueOf(s.minEpisode), "1", InputType.TYPE_CLASS_NUMBER);
        fromCol.addView(fromField);
        toCol.addView(label("To episode"));
        toField = input(String.valueOf(s.maxEpisode), "0 = no limit", InputType.TYPE_CLASS_NUMBER);
        toCol.addView(toField);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        range.addView(fromCol, half);
        View gap = new View(this);
        range.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
        range.addView(toCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        box.addView(range, rlp);
        box.addView(hint("Only episodes in this range are picked. Set \"To\" to 0 for no upper limit."));

        divider(card);
        numberingValue = choiceRow(card, "Episode numbering",
                "How episode numbers are worked out", Settings.numberingLabel(s.numbering),
                v -> pickNumbering());
        divider(card);

        LinearLayout actions = padded(card);
        feedResult = text("", 13, MUTED);
        feedResult.setVisibility(View.GONE);
        actions.addView(feedResult);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        testBtn = pill("Test feed", R.drawable.bg_pill_ghost, TEXT);
        TextView save = pill("Save", R.drawable.bg_pill_gold, 0xFF1C1408);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        b2.setMarginStart(dp(12));
        buttons.addView(testBtn, b1);
        buttons.addView(save, b2);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(4);
        actions.addView(buttons, blp);
        testBtn.setOnClickListener(v -> testFeed());
        save.setOnClickListener(v -> savePodcast());
    }

    private void buildPlayback() {
        LinearLayout card = section("PLAYBACK");
        stepperRow(card, "Episodes per night", "How many random episodes to queue",
                s.queueSize, 1, 100, val -> {
                    Settings.put(this, Settings.K_QUEUE, val);
                    notifyService();
                });
        divider(card);
        switchRow(card, "Start playing when opened", "Open the app at bedtime and it just starts",
                s.autoStart, on -> Settings.put(this, Settings.K_AUTO_START, on));
        divider(card);
        switchRow(card, "Keep going after the queue", "Add another random batch so it never goes quiet",
                s.keepGoing, on -> {
                    Settings.put(this, Settings.K_KEEP_GOING, on);
                    notifyService();
                });
        divider(card);
        TextView[] timer = new TextView[1];
        timer[0] = choiceRow(card, "Sleep timer", "Stop playback after a while",
                Settings.timerLabel(s.sleepTimerMin), v -> {
                    String[] labels = new String[TIMER_MIN.length];
                    int sel = 0;
                    int cur = Settings.load(this).sleepTimerMin;
                    for (int i = 0; i < TIMER_MIN.length; i++) {
                        labels[i] = Settings.timerLabel(TIMER_MIN[i]);
                        if (TIMER_MIN[i] == cur) sel = i;
                    }
                    choose("Sleep timer", labels, sel, which -> {
                        Settings.put(this, Settings.K_SLEEP_TIMER, TIMER_MIN[which]);
                        timer[0].setText(labels[which]);
                        notifyService();
                        if (PlayerService.sRunning) {
                            toast(TIMER_MIN[which] == 0 ? "Sleep timer off"
                                    : "Sleep timer set: " + labels[which] + " from now");
                        }
                    });
                });
        divider(card);
        TextView[] speed = new TextView[1];
        speed[0] = choiceRow(card, "Playback speed", "Slower can be more sleep-friendly",
                Settings.speedLabel(s.speed), v -> {
                    String[] labels = new String[SPEEDS.length];
                    int sel = 2;
                    float cur = Settings.load(this).speed;
                    for (int i = 0; i < SPEEDS.length; i++) {
                        labels[i] = Settings.speedLabel(SPEEDS[i]);
                        if (Math.abs(SPEEDS[i] - cur) < 0.01f) sel = i;
                    }
                    choose("Playback speed", labels, sel, which -> {
                        Settings.put(this, Settings.K_SPEED, SPEEDS[which]);
                        speed[0].setText(labels[which]);
                        notifyService();
                    });
                });
        divider(card);
        switchRow(card, "Pause when headphones unplug", "Stops it blaring out of the speaker",
                s.pauseOnUnplug, on -> {
                    Settings.put(this, Settings.K_PAUSE_UNPLUG, on);
                    notifyService();
                });
    }

    private void buildDownloads() {
        LinearLayout card = section("DOWNLOADS & DATA");
        switchRow(card, "Download queue ahead on Wi-Fi",
                "Episodes keep playing even if Wi-Fi drops overnight", s.downloadAhead, on -> {
                    Settings.put(this, Settings.K_DOWNLOAD_AHEAD, on);
                    notifyService();
                });
        divider(card);
        switchRow(card, "Stream on mobile data",
                "Off: away from Wi-Fi, only saved episodes play", s.allowMobile, on -> {
                    Settings.put(this, Settings.K_ALLOW_MOBILE, on);
                    notifyService();
                });
        divider(card);
        TextView[] maxV = new TextView[1];
        maxV[0] = choiceRow(card, "Keep up to", "Oldest saved episodes are removed first",
                s.maxCached + " episodes", v -> {
                    String[] labels = new String[MAX_SAVED.length];
                    int sel = 0;
                    int cur = Settings.load(this).maxCached;
                    for (int i = 0; i < MAX_SAVED.length; i++) {
                        labels[i] = MAX_SAVED[i] + " episodes";
                        if (MAX_SAVED[i] == cur) sel = i;
                    }
                    choose("Keep up to", labels, sel, which -> {
                        Settings.put(this, Settings.K_MAX_CACHED, MAX_SAVED[which]);
                        maxV[0].setText(labels[which]);
                        notifyService();
                    });
                });
        divider(card);

        LinearLayout row = row(card);
        LinearLayout texts = column();
        texts.addView(text("Storage used", 15, TEXT));
        storageValue = text("", 12, MUTED);
        texts.addView(storageValue);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = pill("Clear", R.drawable.bg_pill_stop, 0xFFF2B6A3);
        clear.setPadding(dp(18), 0, dp(18), 0);
        row.addView(clear, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
        clear.setOnClickListener(v -> {
            long[] st = Downloads.stats(this);
            if (st[1] == 0) {
                toast("Nothing downloaded yet");
                return;
            }
            new AlertDialog.Builder(this, dialogTheme())
                    .setTitle("Clear downloads?")
                    .setMessage("Delete " + st[1] + " saved episodes (" + Downloads.formatBytes(st[0])
                            + ")? They'll download again on Wi-Fi when they're picked.")
                    .setPositiveButton("Delete", (d, w) -> {
                        int n = Downloads.clearAll(this);
                        updateStorage();
                        toast("Removed " + n + " episodes");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        updateStorage();
    }

    private void buildEpisodeList() {
        LinearLayout card = section("EPISODE LIST");
        TextView[] refV = new TextView[1];
        refV[0] = choiceRow(card, "Check feed for new episodes",
                "Finished shows only need fetching once", Settings.refreshLabel(s.refreshDays), v -> {
                    String[] labels = new String[REFRESH_DAYS.length];
                    int sel = 0;
                    int cur = Settings.load(this).refreshDays;
                    for (int i = 0; i < REFRESH_DAYS.length; i++) {
                        labels[i] = Settings.refreshLabel(REFRESH_DAYS[i]);
                        if (REFRESH_DAYS[i] == cur) sel = i;
                    }
                    choose("Check for new episodes", labels, sel, which -> {
                        Settings.put(this, Settings.K_REFRESH_DAYS, REFRESH_DAYS[which]);
                        refV[0].setText(labels[which]);
                    });
                });
        divider(card);
        LinearLayout row = clickableRow(card);
        LinearLayout texts = column();
        texts.addView(text("Refresh episode list now", 15, TEXT));
        refreshResult = text("Re-download the feed and recount episodes", 12, MUTED);
        texts.addView(refreshResult);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(icon(R.drawable.ic_refresh, GOLD));
        row.setOnClickListener(v -> refreshNow());
    }

    private void buildAbout() {
        LinearLayout card = section("ABOUT");
        choiceRow(card, "About & disclaimer", "AI-generated code: please read",
                "", v -> MainActivity.showAbout(this));
        divider(card);
        choiceRow(card, "Project page", getString(R.string.project_url), "", v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(getString(R.string.project_url))));
            } catch (Exception ignored) {
            }
        });
        divider(card);
        LinearLayout row = row(card);
        row.addView(text("Version", 15, TEXT), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text(MainActivity.versionName(this), 14, MUTED));
    }

    // ------------------------------------------------------------------ actions

    /** Reads the podcast fields into a Settings copy; returns null (and says why) if invalid. */
    private Settings readPodcastFields() {
        Settings t = Settings.load(this);
        t.feedUrl = feedField.getText().toString().trim();
        try {
            t.minEpisode = Integer.parseInt(fromField.getText().toString().trim());
            String to = toField.getText().toString().trim();
            t.maxEpisode = to.isEmpty() ? 0 : Integer.parseInt(to);
        } catch (NumberFormatException e) {
            showFeedResult("Episode numbers must be whole numbers.", WARN);
            return null;
        }
        t.numbering = pendingNumbering;
        String problem = t.validate();
        if (problem != null) {
            showFeedResult(problem, WARN);
            return null;
        }
        return t;
    }

    private void testFeed() {
        Settings t = readPodcastFields();
        if (t == null) return;
        testBtn.setEnabled(false);
        testBtn.setText("Checking…");
        showFeedResult("Reading the feed…", MUTED);
        new Thread(() -> {
            String msg;
            int color;
            try {
                FeedRepository.Feed f = FeedRepository.load(this, t, true);
                msg = "✓  " + f.title + "\n" + f.episodes.size() + " episodes in " + t.rangeLabel()
                        + " · numbered by " + ("tag".equals(f.numberingUsed)
                        ? "the feed's own numbers" : "release order");
                color = f.episodes.isEmpty() ? WARN : GOOD;
                if (f.episodes.isEmpty()) msg += "\nTry a different range or numbering.";
            } catch (Exception e) {
                msg = "✗  Couldn't read this feed: " + e.getMessage();
                color = WARN;
            }
            final String m = msg;
            final int c = color;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                testBtn.setEnabled(true);
                testBtn.setText("Test feed");
                showFeedResult(m, c);
            });
        }, "feed-test").start();
    }

    private void savePodcast() {
        Settings t = readPodcastFields();
        if (t == null) return;
        Settings old = Settings.load(this);
        boolean changed = !old.feedUrl.equals(t.feedUrl) || old.minEpisode != t.minEpisode
                || old.maxEpisode != t.maxEpisode || !old.numbering.equals(t.numbering);
        t.savePodcast(this);
        showFeedResult("Saved.", GOOD);
        if (changed && PlayerService.sRunning) {
            new AlertDialog.Builder(this, dialogTheme())
                    .setTitle("Start a new queue?")
                    .setMessage("Tonight's queue was picked with the old settings. "
                            + "Shuffle a new one now?")
                    .setPositiveButton("Shuffle now", (d, w) -> {
                        startForegroundService(new Intent(this, PlayerService.class)
                                .setAction(PlayerService.ACTION_START));
                        finish();
                    })
                    .setNegativeButton("Later", null)
                    .show();
        } else {
            toast(changed ? "Podcast settings saved" : "No changes");
        }
    }

    private void pickNumbering() {
        String[] labels = {
                "Automatic\nFeed numbers if most episodes have one, otherwise release order",
                "Feed's own numbers\nUse <itunes:episode> or \"#123\" in titles; skip unnumbered",
                "Release order\nOldest episode is #1, the next is #2, and so on"
        };
        int sel = 0;
        for (int i = 0; i < Settings.NUMBERING_MODES.length; i++) {
            if (Settings.NUMBERING_MODES[i].equals(pendingNumbering)) sel = i;
        }
        choose("Episode numbering", labels, sel, which -> {
            pendingNumbering = Settings.NUMBERING_MODES[which];
            numberingValue.setText(Settings.numberingLabel(pendingNumbering));
            showFeedResult("Tap Save to use the new numbering.", MUTED);
        });
    }

    private void refreshNow() {
        Settings cur = Settings.load(this);
        if (cur.validate() != null) {
            refreshResult.setText(cur.validate());
            return;
        }
        refreshResult.setText("Refreshing…");
        refreshResult.setTextColor(MUTED);
        new Thread(() -> {
            String msg;
            int color;
            try {
                FeedRepository.Feed f = FeedRepository.load(this, cur, true);
                Settings.put(this, Settings.K_LAST_SHOW, f.title);
                msg = "Updated · " + f.episodes.size() + " episodes in " + cur.rangeLabel();
                color = GOOD;
            } catch (Exception e) {
                msg = "Couldn't refresh: " + e.getMessage();
                color = WARN;
            }
            final String m = msg;
            final int c = color;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                refreshResult.setText(m);
                refreshResult.setTextColor(c);
            });
        }, "feed-refresh").start();
    }

    private void updateStorage() {
        if (storageValue == null) return;
        long[] st = Downloads.stats(this);
        storageValue.setText(st[1] == 0 ? "No episodes saved yet"
                : Downloads.formatBytes(st[0]) + " · " + st[1] + " episodes saved");
    }

    private void notifyService() {
        if (PlayerService.sRunning) {
            startForegroundService(new Intent(this, PlayerService.class)
                    .setAction(PlayerService.ACTION_SETTINGS_CHANGED));
        }
    }

    private void showFeedResult(String msg, int color) {
        feedResult.setVisibility(View.VISIBLE);
        feedResult.setText(msg);
        feedResult.setTextColor(color);
    }

    // ------------------------------------------------------------------ building blocks

    interface IntCallback { void on(int value); }
    interface BoolCallback { void on(boolean value); }

    private LinearLayout section(String title) {
        TextView h = text(title, 11, GOLD);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setLetterSpacing(0.2f);
        h.setPadding(dp(8), dp(26), 0, dp(10));
        content.addView(h);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setClipToOutline(true);
        card.setPadding(0, dp(4), 0, dp(4));
        content.addView(card);
        return card;
    }

    private LinearLayout padded(LinearLayout card) {
        LinearLayout box = column();
        box.setPadding(dp(18), dp(14), dp(18), dp(14));
        card.addView(box);
        return box;
    }

    private LinearLayout row(LinearLayout card) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(64));
        r.setPadding(dp(18), dp(12), dp(14), dp(12));
        card.addView(r);
        return r;
    }

    private LinearLayout clickableRow(LinearLayout card) {
        LinearLayout r = row(card);
        r.setBackgroundResource(R.drawable.bg_row);
        r.setClickable(true);
        r.setFocusable(true);
        return r;
    }

    private void switchRow(LinearLayout card, String title, String sub, boolean checked,
                           BoolCallback cb) {
        LinearLayout r = clickableRow(card);
        r.addView(titleBlock(title, sub), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setThumbTintList(getColorStateList(R.color.switch_thumb));
        sw.setTrackTintList(getColorStateList(R.color.switch_track));
        sw.setOnCheckedChangeListener((b, on) -> cb.on(on));
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
    }

    private TextView choiceRow(LinearLayout card, String title, String sub, String value,
                               View.OnClickListener click) {
        LinearLayout r = clickableRow(card);
        r.addView(titleBlock(title, sub), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value, 14, GOLD);
        v.setGravity(Gravity.END);
        v.setMaxWidth(dp(150));
        v.setPadding(dp(10), 0, dp(4), 0);
        r.addView(v);
        r.addView(icon(R.drawable.ic_chevron, MUTED));
        r.setOnClickListener(click);
        return v;
    }

    private TextView stepperRow(LinearLayout card, String title, String sub, int value,
                                int min, int max, IntCallback cb) {
        LinearLayout r = row(card);
        r.addView(titleBlock(title, sub), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView minus = roundButton("−");
        TextView val = text(String.valueOf(value), 17, GOLD);
        val.setGravity(Gravity.CENTER);
        val.setMinWidth(dp(40));
        TextView plus = roundButton("+");
        r.addView(minus, new LinearLayout.LayoutParams(dp(38), dp(38)));
        r.addView(val);
        r.addView(plus, new LinearLayout.LayoutParams(dp(38), dp(38)));
        final int[] cur = {value};
        View.OnClickListener step = v -> {
            int delta = v == plus ? 1 : -1;
            if (cur[0] >= 20 && v == plus) delta = 5;
            if (cur[0] > 20 && v == minus) delta = -5;
            int next = Math.max(min, Math.min(max, cur[0] + delta));
            if (next == cur[0]) return;
            cur[0] = next;
            val.setText(String.valueOf(next));
            cb.on(next);
        };
        minus.setOnClickListener(step);
        plus.setOnClickListener(step);
        return val;
    }

    private LinearLayout titleBlock(String title, String sub) {
        LinearLayout col = column();
        col.addView(text(title, 15, TEXT));
        if (sub != null && !sub.isEmpty()) {
            TextView st = text(sub, 12, MUTED);
            st.setPadding(0, dp(2), dp(8), 0);
            col.addView(st);
        }
        return col;
    }

    private TextView roundButton(String label) {
        TextView b = text(label, 18, SOFT);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.bg_circle);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView pill(String label, int bg, int color) {
        TextView b = text(label, 15, color);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackgroundResource(bg);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private EditText input(String value, String hint, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setTextColor(TEXT);
        e.setHintTextColor(0xFF556075);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        e.setLayoutParams(lp);
        return e;
    }

    private TextView label(String t) {
        TextView v = text(t, 13, SOFT);
        v.setPadding(dp(2), dp(8), 0, 0);
        return v;
    }

    private TextView hint(String t) {
        TextView v = text(t, 12, MUTED);
        v.setPadding(dp(2), dp(6), 0, dp(4));
        return v;
    }

    private TextView text(String t, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        return v;
    }

    private View icon(int res, int tint) {
        android.widget.ImageView i = new android.widget.ImageView(this);
        i.setImageResource(res);
        i.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
        return i;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private void divider(LinearLayout card) {
        View d = new View(this);
        d.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.setMarginStart(dp(18));
        lp.setMarginEnd(dp(18));
        card.addView(d, lp);
    }

    private void choose(String title, String[] labels, int selected, IntCallback cb) {
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle(title)
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    cb.on(which);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static int dialogTheme() {
        return android.R.style.Theme_Material_Dialog_Alert;
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
