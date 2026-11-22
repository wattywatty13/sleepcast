package com.sleepcast.player;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Opening the app at bedtime starts a fresh random queue automatically.
 * If a session is already playing (e.g. you open it in the morning), it just shows the controls.
 */
public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView greeting, showSubtitle, idleStatus, sourceChip, epNumber, epTitle, statusLine;
    private TextView timePos, timeLeft, chipQueue, chipSaved, chipTimer, downloadInfo, drawerShow;
    private View idlePanel, playPanel, upNextCard, drawer, scrim;
    private LinearLayout upNextList;
    private ProgressBar progress;
    private ImageButton pauseBtn;
    private String lastUpNext = null;
    private boolean lastPaused = false;
    private boolean drawerOpen = false;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        greeting = findViewById(R.id.greeting);
        showSubtitle = findViewById(R.id.show_subtitle);
        idleStatus = findViewById(R.id.idle_status);
        sourceChip = findViewById(R.id.source_chip);
        epNumber = findViewById(R.id.ep_number);
        epTitle = findViewById(R.id.ep_title);
        statusLine = findViewById(R.id.status_line);
        timePos = findViewById(R.id.time_pos);
        timeLeft = findViewById(R.id.time_left);
        chipQueue = findViewById(R.id.chip_queue);
        chipSaved = findViewById(R.id.chip_saved);
        chipTimer = findViewById(R.id.chip_timer);
        downloadInfo = findViewById(R.id.download_info);
        idlePanel = findViewById(R.id.idle_panel);
        playPanel = findViewById(R.id.play_panel);
        upNextCard = findViewById(R.id.up_next_card);
        upNextList = findViewById(R.id.up_next_list);
        progress = findViewById(R.id.progress);
        pauseBtn = findViewById(R.id.btn_pause);
        drawer = findViewById(R.id.drawer);
        scrim = findViewById(R.id.drawer_scrim);
        drawerShow = findViewById(R.id.drawer_show);

        findViewById(R.id.btn_stop).setOnClickListener(v -> send(PlayerService.ACTION_STOP));
        findViewById(R.id.btn_next).setOnClickListener(v -> send(PlayerService.ACTION_NEXT));
        findViewById(R.id.btn_shuffle).setOnClickListener(v -> send(PlayerService.ACTION_START));
        findViewById(R.id.btn_start).setOnClickListener(v -> startOrOpenSettings());
        pauseBtn.setOnClickListener(v -> send(PlayerService.ACTION_TOGGLE));

        // Side menu
        findViewById(R.id.btn_menu).setOnClickListener(v -> openDrawer());
        scrim.setOnClickListener(v -> closeDrawer());
        findViewById(R.id.menu_now).setOnClickListener(v -> closeDrawer());
        findViewById(R.id.menu_settings).setOnClickListener(v -> {
            closeDrawer();
            startActivity(new Intent(this, SettingsActivity.class));
        });
        findViewById(R.id.menu_refresh).setOnClickListener(v -> {
            closeDrawer();
            startOrOpenSettings();
        });
        findViewById(R.id.menu_help).setOnClickListener(v -> {
            closeDrawer();
            showHelp();
        });
        findViewById(R.id.menu_about).setOnClickListener(v -> {
            closeDrawer();
            showAbout(this);
        });
        ((TextView) findViewById(R.id.menu_version)).setText("Version " + versionName(this));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        Settings s = Settings.load(this);
        if (s.validate() != null) {
            // First run with no usable feed: go straight to Settings.
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (savedInstanceState == null && s.autoStart && !PlayerService.sRunning) {
            send(PlayerService.ACTION_START);
        }
        refresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Tapping the home-screen icon again while the app is still in memory counts as "opening it".
        Settings s = Settings.load(this);
        if (Intent.ACTION_MAIN.equals(intent.getAction()) && s.autoStart
                && s.validate() == null && !PlayerService.sRunning) {
            send(PlayerService.ACTION_START);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (drawerOpen) closeDrawer();
        else super.onBackPressed();
    }

    private void startOrOpenSettings() {
        if (Settings.load(this).validate() != null) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else {
            send(PlayerService.ACTION_START);
        }
    }

    private void send(String action) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        startForegroundService(i);
        handler.postDelayed(this::refresh, 200);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    // ------------------------------------------------------------------ drawer

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        float w = drawer.getWidth() > 0 ? drawer.getWidth() : dp(300);
        drawer.setTranslationX(-w);
        drawer.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        drawer.animate().translationX(0).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        scrim.animate().alpha(1f).setDuration(260).start();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawer.animate().translationX(-drawer.getWidth()).setDuration(200)
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
        scrim.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> scrim.setVisibility(View.GONE)).start();
    }

    // ------------------------------------------------------------------ dialogs

    private void showHelp() {
        Settings s = Settings.load(this);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("How it works")
                .setMessage("1.  Open the app at bedtime. It picks " + s.queueSize
                        + " random episodes from " + s.rangeLabel() + " and starts playing.\n\n"
                        + "2.  On Wi-Fi, it downloads the queue in the background, so playback "
                        + "keeps going even if Wi-Fi drops overnight.\n\n"
                        + "3.  When the queue runs out it adds another batch (you can turn this "
                        + "off in Settings), or a sleep timer can stop it for you.\n\n"
                        + "4.  In the morning, tap Stop here or in the notification.\n\n"
                        + "Tip: set the app's battery usage to \"Unrestricted\" in your phone's "
                        + "settings so it isn't stopped overnight.")
                .setPositiveButton("Got it", null)
                .show();
    }

    static void showAbout(Activity a) {
        String url = a.getString(R.string.project_url);
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(a.getString(R.string.app_name) + " " + versionName(a))
                .setMessage("A bedtime podcast player that shuffles random episodes from any "
                        + "podcast's RSS feed and plays them all night.\n\n"
                        + "⚠️  AI-generated code: this app was \"vibe coded\" with an AI assistant "
                        + "(Claude, by Anthropic) and hasn't been reviewed line by line. It's "
                        + "provided as-is with no warranty. Watch your data use and storage, "
                        + "and use it at your own risk.\n\n"
                        + "Podcasts belong to their creators; this app only plays public feeds "
                        + "the way any podcast app does.")
                .setPositiveButton("Close", null)
                .setNeutralButton("Project page", (d, w) -> {
                    try {
                        a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored) {
                    }
                })
                .show();
    }

    static String versionName(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ screen

    private void refresh() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(hour >= 5 && hour < 12 ? "GOOD MORNING"
                : hour >= 12 && hour < 18 ? "GOOD AFTERNOON" : "GOOD NIGHT");

        Settings s = Settings.load(this);
        String show = Settings.lastShowTitle(this);
        String sub = (show.isEmpty() ? "Random episodes" : show) + "  ·  " + s.rangeLabel();
        showSubtitle.setText(sub);
        drawerShow.setText(sub);

        boolean running = PlayerService.sRunning;
        idlePanel.setVisibility(running ? View.GONE : View.VISIBLE);
        playPanel.setVisibility(running ? View.VISIBLE : View.GONE);
        if (!running) {
            String problem = s.validate();
            idleStatus.setText(problem != null ? problem
                    : PlayerService.sStatus.isEmpty()
                    ? s.queueSize + " random episodes, downloaded on Wi-Fi, all night long."
                    : PlayerService.sStatus);
            ((TextView) findViewById(R.id.btn_start)).setText(problem != null
                    ? "Open Settings" : "Start tonight's " + s.queueSize + " episodes");
            lastUpNext = null;
            return;
        }

        String status = PlayerService.sStatus;
        boolean paused = PlayerService.sPaused;
        int number = PlayerService.sEpisodeNumber;

        if (number <= 0) {
            epNumber.setText("—");
            epTitle.setText("Shuffling tonight's episodes…");
            sourceChip.setVisibility(View.GONE);
        } else {
            epNumber.setText("#" + number);
            epTitle.setText(PlayerService.sEpisodeTitle);
            sourceChip.setVisibility(View.VISIBLE);
            sourceChip.setText(paused ? "PAUSED" : PlayerService.sStreaming ? "STREAMING" : "SAVED");
        }

        boolean notable = !status.startsWith("Playing") && !status.equals("Paused");
        statusLine.setVisibility(notable && !status.isEmpty() ? View.VISIBLE : View.GONE);
        statusLine.setText(status);

        if (paused != lastPaused) {
            pauseBtn.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
            lastPaused = paused;
        }

        long[] p = PlayerService.progress();
        if (p != null && p[1] > 0) {
            progress.setProgress((int) (p[0] * 1000 / p[1]));
            timePos.setText(formatTime(p[0]));
            timeLeft.setText("-" + formatTime(Math.max(0, p[1] - p[0])));
        } else {
            progress.setProgress(0);
            timePos.setText("0:00");
            timeLeft.setText("");
        }

        int size = PlayerService.sQueueSize;
        chipQueue.setText(size > 0
                ? String.format(Locale.US, "%d of %d", PlayerService.sIndex + 1, size) : "Queue");
        chipSaved.setText(size > 0
                ? String.format(Locale.US, "%d saved", PlayerService.sCachedCount) : "Saving…");
        downloadInfo.setText(PlayerService.sDownloadInfo);

        long ends = PlayerService.sSleepEndsAt;
        chipTimer.setVisibility(ends > 0 ? View.VISIBLE : View.GONE);
        if (ends > 0) {
            chipTimer.setText("Sleep timer · stops at "
                    + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(ends)));
        }

        String upNext = PlayerService.sUpNext;
        if (!upNext.equals(lastUpNext)) {
            lastUpNext = upNext;
            buildUpNext(upNext);
        }
    }

    private void buildUpNext(String data) {
        upNextList.removeAllViews();
        if (data.isEmpty()) {
            upNextCard.setVisibility(View.GONE);
            return;
        }
        upNextCard.setVisibility(View.VISIBLE);
        for (String line : data.split("\n")) {
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) continue;
            upNextList.addView(upNextRow(parts[0], parts[1], "1".equals(parts[2])));
        }
    }

    private View upNextRow(String number, String title, boolean saved) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        TextView num = new TextView(this);
        num.setText("#" + number);
        num.setTextColor(0xFFE8C887);
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        num.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        num.setMinWidth(dp(54));
        row.addView(num);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFC9CFDB);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        View dot = new View(this);
        dot.setBackgroundResource(saved ? R.drawable.dot_saved : R.drawable.dot_stream);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(7), dp(7));
        dl.setMarginStart(dp(12));
        row.addView(dot, dl);
        return row;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String formatTime(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }
}
