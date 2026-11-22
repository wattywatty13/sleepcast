package com.sleepcast.player;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Opening the app at bedtime starts a fresh random queue automatically.
 * If a session is already playing (e.g. you open it in the morning), it just shows the controls.
 */
public class MainActivity extends Activity {

    private static final String[] NUMBERING_LABELS = {
            "Auto (feed numbers if available)",
            "Feed's own episode numbers only",
            "Release order (oldest = 1)"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, nowPlaying, queueInfo, downloadInfo, showInfo, subtitle;
    private Button stopBtn, nextBtn, pauseBtn, startBtn, settingsToggle;
    private View settingsPanel;
    private EditText feedUrl, minEp, maxEp, queueSize;
    private Spinner numbering;

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

        status = findViewById(R.id.status);
        nowPlaying = findViewById(R.id.now_playing);
        queueInfo = findViewById(R.id.queue_info);
        downloadInfo = findViewById(R.id.download_info);
        showInfo = findViewById(R.id.show_info);
        subtitle = findViewById(R.id.subtitle);
        stopBtn = findViewById(R.id.btn_stop);
        nextBtn = findViewById(R.id.btn_next);
        pauseBtn = findViewById(R.id.btn_pause);
        startBtn = findViewById(R.id.btn_start);
        settingsToggle = findViewById(R.id.btn_settings);
        settingsPanel = findViewById(R.id.settings_panel);
        feedUrl = findViewById(R.id.feed_url);
        minEp = findViewById(R.id.min_episode);
        maxEp = findViewById(R.id.max_episode);
        queueSize = findViewById(R.id.queue_size);
        numbering = findViewById(R.id.numbering);
        CheckBox mobile = findViewById(R.id.allow_mobile);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, NUMBERING_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        numbering.setAdapter(adapter);
        fillSettingsForm(Settings.load(this));

        mobile.setChecked(Settings.prefs(this).getBoolean(Settings.K_ALLOW_MOBILE, false));
        mobile.setOnCheckedChangeListener((b, checked) ->
                Settings.prefs(this).edit().putBoolean(Settings.K_ALLOW_MOBILE, checked).apply());

        stopBtn.setOnClickListener(v -> send(PlayerService.ACTION_STOP));
        nextBtn.setOnClickListener(v -> send(PlayerService.ACTION_NEXT));
        pauseBtn.setOnClickListener(v -> send(PlayerService.ACTION_TOGGLE));
        startBtn.setOnClickListener(v -> send(PlayerService.ACTION_START));
        settingsToggle.setOnClickListener(v -> settingsPanel.setVisibility(
                settingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        findViewById(R.id.btn_save).setOnClickListener(v -> saveSettings());
        findViewById(R.id.btn_reset).setOnClickListener(v -> {
            Settings.resetToDefaults(this);
            fillSettingsForm(Settings.load(this));
            Toast.makeText(this, "Back to the built-in defaults", Toast.LENGTH_SHORT).show();
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        Settings s = Settings.load(this);
        if (s.validate() != null) {
            settingsPanel.setVisibility(View.VISIBLE); // first run with no feed set: show settings
        } else if (savedInstanceState == null && s.autoStart && !PlayerService.sRunning) {
            send(PlayerService.ACTION_START);
        }
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

    private void fillSettingsForm(Settings s) {
        feedUrl.setText(s.feedUrl);
        minEp.setText(String.valueOf(s.minEpisode));
        maxEp.setText(String.valueOf(s.maxEpisode));
        queueSize.setText(String.valueOf(s.queueSize));
        int idx = 0;
        for (int i = 0; i < Settings.NUMBERING_MODES.length; i++) {
            if (Settings.NUMBERING_MODES[i].equals(s.numbering)) idx = i;
        }
        numbering.setSelection(idx);
    }

    private void saveSettings() {
        Settings s = Settings.load(this);
        s.feedUrl = feedUrl.getText().toString().trim();
        try {
            s.minEpisode = Integer.parseInt(minEp.getText().toString().trim());
            String max = maxEp.getText().toString().trim();
            s.maxEpisode = max.isEmpty() ? 0 : Integer.parseInt(max);
            s.queueSize = Integer.parseInt(queueSize.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Episode numbers and queue size must be whole numbers",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (s.queueSize < 1) {
            Toast.makeText(this, "Queue size must be at least 1", Toast.LENGTH_LONG).show();
            return;
        }
        s.numbering = Settings.NUMBERING_MODES[numbering.getSelectedItemPosition()];
        String problem = s.validate();
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
            return;
        }
        s.save(this);
        settingsPanel.setVisibility(View.GONE);
        Toast.makeText(this, "Saved. Starting a new queue…", Toast.LENGTH_SHORT).show();
        send(PlayerService.ACTION_START);
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

    private void refresh() {
        boolean running = PlayerService.sRunning;
        Settings s = Settings.load(this);
        subtitle.setText("Random episodes from " + s.rangeLabel());
        status.setText(PlayerService.sStatus);
        nowPlaying.setText(running ? PlayerService.sNowPlaying : "");
        queueInfo.setText(running ? PlayerService.sQueueInfo : "");
        downloadInfo.setText(running ? PlayerService.sDownloadInfo : "");
        showInfo.setText(running ? PlayerService.sShowInfo : "");
        stopBtn.setVisibility(running ? View.VISIBLE : View.GONE);
        nextBtn.setVisibility(running ? View.VISIBLE : View.GONE);
        pauseBtn.setVisibility(running ? View.VISIBLE : View.GONE);
        pauseBtn.setText(PlayerService.sPaused ? "Resume" : "Pause");
        startBtn.setText(running ? "Shuffle a new queue" : "Start bedtime queue");
    }
}
