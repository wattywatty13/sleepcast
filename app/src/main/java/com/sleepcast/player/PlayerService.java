package com.sleepcast.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground service that builds a random queue, downloads it on Wi-Fi and plays it all night.
 */
public class PlayerService extends Service {

    public static final String ACTION_START = "com.sleepcast.player.START";
    public static final String ACTION_STOP = "com.sleepcast.player.STOP";
    public static final String ACTION_NEXT = "com.sleepcast.player.NEXT";
    public static final String ACTION_TOGGLE = "com.sleepcast.player.TOGGLE";

    private static final long MIN_FREE_BYTES = 500L * 1024 * 1024;
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "playback";

    // ---- State the activity reads to draw the screen ----
    public static volatile boolean sRunning = false;
    public static volatile boolean sPaused = false;
    public static volatile String sStatus = "";
    public static volatile String sNowPlaying = "";
    public static volatile String sQueueInfo = "";
    public static volatile String sDownloadInfo = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Episode> queue = new CopyOnWriteArrayList<>();
    private volatile int index = -1;
    private volatile boolean running = false;
    private boolean paused = false;
    private boolean pausedForFocus = false;
    private int consecutiveErrors = 0;
    private int sessionId = 0;

    private List<Episode> allEpisodes = new ArrayList<>();
    private Settings settings;
    private String showTitle = "Podcast";
    public static volatile String sShowInfo = "";
    private MediaPlayer player;
    private boolean prepared = false;
    private final java.util.concurrent.ConcurrentHashMap<Integer, Integer> downloadFailures =
            new java.util.concurrent.ConcurrentHashMap<>();
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private WifiManager.WifiLock wifiLock;
    private Thread downloader;
    private final Object downloadSignal = new Object();
    private boolean noisyRegistered = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createChannel();
        session = new MediaSession(this, "SleepCast");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onStop() { stopEverything(); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (action == null) action = ACTION_START;

        // Always satisfy the foreground-service contract right away.
        goForeground("Getting ready…");

        switch (action) {
            case ACTION_STOP:
                stopEverything();
                break;
            case ACTION_NEXT:
                if (running) next(); else stopEverything();
                break;
            case ACTION_TOGGLE:
                if (!running) stopEverything();
                else if (paused) resume();
                else pause();
                break;
            case ACTION_START:
            default:
                startSession();
                break;
        }
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ session

    private void startSession() {
        main.removeCallbacksAndMessages(null);
        downloadFailures.clear();
        releasePlayer();
        stopDownloader();
        running = true;
        sRunning = true;
        paused = false;
        sPaused = false;
        consecutiveErrors = 0;
        queue.clear();
        index = -1;
        setStatus("Loading episode list…");

        acquireWifiLock();
        registerNoisy();

        settings = Settings.load(this);
        String problem = settings.validate();
        if (problem != null) {
            failAndStop(problem);
            return;
        }
        final Settings mySettings = settings;
        final int mySession = ++sessionId;
        new Thread(() -> {
            try {
                FeedRepository.Feed feed = FeedRepository.load(this, mySettings, false);
                cleanPartials();
                main.post(() -> {
                    if (!running || mySession != sessionId) return;
                    allEpisodes = feed.episodes;
                    showTitle = feed.title;
                    sShowInfo = feed.title + " · " + feed.episodes.size() + " episodes in "
                            + mySettings.rangeLabel() + " (numbered by "
                            + ("tag".equals(feed.numberingUsed) ? "the feed's episode numbers"
                            : "release order") + ")";
                    if (allEpisodes.isEmpty()) {
                        failAndStop("No episodes found in " + mySettings.rangeLabel()
                                + ". Check the range or numbering in Settings.");
                        return;
                    }
                    List<Episode> picked = pickRandom(settings.queueSize, Collections.emptySet());
                    if (picked.isEmpty()) {
                        failAndStop("Not on Wi-Fi and nothing is cached yet. "
                                + "Connect to Wi-Fi (or allow mobile data) and try again.");
                        return;
                    }
                    queue.addAll(picked);
                    startDownloader();
                    requestFocus();
                    playIndex(0);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (mySession == sessionId) {
                        failAndStop("Couldn't load the episode list: " + e.getMessage());
                    }
                });
            }
        }, "feed-loader").start();
    }

    /**
     * Picks random episodes. If we can stream, any of 1..700 is fair game.
     * If we can't (no Wi-Fi, mobile streaming off), only episodes already on the phone.
     */
    private List<Episode> pickRandom(int count, Set<Integer> exclude) {
        List<Episode> pool = new ArrayList<>();
        boolean canStream = canStream();
        for (Episode e : allEpisodes) {
            if (exclude.contains(e.number)) continue;
            if (canStream || isCached(e)) pool.add(e);
        }
        if (pool.isEmpty() && !exclude.isEmpty()) return pickRandom(count, Collections.emptySet());
        Collections.shuffle(pool);
        return new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
    }

    private void playIndex(int i) {
        if (!running) return;
        if (i >= queue.size()) {
            // Ran through the whole queue: keep the background noise going with a fresh batch.
            Set<Integer> recent = new HashSet<>();
            for (Episode e : queue) recent.add(e.number);
            List<Episode> more = pickRandom(settings.queueSize, recent);
            if (more.isEmpty()) {
                setStatus("Waiting for Wi-Fi…");
                main.postDelayed(() -> playIndex(i), 60_000);
                return;
            }
            queue.addAll(more);
            signalDownloader();
        }
        index = i;
        Episode ep = queue.get(i);

        String source;
        File local = cachedFile(ep);
        if (local.exists()) {
            source = local.getAbsolutePath();
        } else if (canStream()) {
            source = ep.url;
        } else {
            // Can't stream right now: jump to the next episode that is already downloaded.
            int nextCached = -1;
            for (int j = i + 1; j < queue.size(); j++) {
                if (isCached(queue.get(j))) { nextCached = j; break; }
            }
            if (nextCached >= 0) {
                final int target = nextCached;
                main.post(() -> playIndex(target));
            } else {
                releasePlayer();
                setStatus("Waiting for Wi-Fi…");
                main.postDelayed(() -> playIndex(i), 60_000);
            }
            return;
        }

        releasePlayer();
        sNowPlaying = ep.displayTitle();
        updateQueueInfo();
        setStatus(local.exists() ? "Playing (downloaded)" : "Playing (streaming)");
        updateSession(ep);

        MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(p -> {
            if (player != p || !running) return;
            prepared = true;
            consecutiveErrors = 0;
            if (!paused) p.start();
            updatePlaybackState();
        });
        mp.setOnCompletionListener(p -> {
            if (player != p || !running) return;
            playIndex(index + 1);
        });
        mp.setOnErrorListener((p, what, extra) -> {
            if (player != p || !running) return true;
            consecutiveErrors++;
            if (consecutiveErrors >= 8) {
                // Probably the connection dropped. Back off, then try again.
                setStatus("Having trouble playing; retrying in a minute…");
                consecutiveErrors = 0;
                final int retry = index;
                releasePlayer();
                main.postDelayed(() -> playIndex(retry), 60_000);
            } else {
                final int skip = index + 1;
                releasePlayer();
                main.postDelayed(() -> playIndex(skip), 2_000);
            }
            return true;
        });
        try {
            mp.setDataSource(source);
            mp.prepareAsync();
        } catch (Exception e) {
            consecutiveErrors++;
            releasePlayer();
            main.postDelayed(() -> playIndex(i + 1), 2_000);
        }
        signalDownloader();
    }

    private void next() {
        if (!running || index < 0) return;
        playIndex(index + 1);
    }

    private void pause() {
        if (!running) return;
        paused = true;
        sPaused = true;
        if (player != null && prepared && player.isPlaying()) player.pause();
        setStatus("Paused");
        updatePlaybackState();
    }

    private void resume() {
        if (!running) return;
        paused = false;
        sPaused = false;
        pausedForFocus = false;
        requestFocus();
        // If the episode is still loading, onPrepared will start it.
        if (player != null && prepared) {
            try { player.start(); } catch (IllegalStateException ignored) { }
        }
        setStatus("Playing");
        updatePlaybackState();
    }

    private void failAndStop(String message) {
        stopEverything();
        sStatus = message;
    }

    private void stopEverything() {
        running = false;
        sRunning = false;
        sPaused = false;
        main.removeCallbacksAndMessages(null);
        stopDownloader();
        releasePlayer();
        abandonFocus();
        releaseWifiLock();
        unregisterNoisy();
        sNowPlaying = "";
        sQueueInfo = "";
        sDownloadInfo = "";
        sShowInfo = "";
        sStatus = "Stopped. Good morning!";
        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0, 0f).build());
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (running) stopEverything();
        if (session != null) {
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) { }
            player.release();
            player = null;
        }
    }

    // ------------------------------------------------------------------ downloads (Wi-Fi only)

    private void startDownloader() {
        stopDownloader();
        downloader = new Thread(this::downloadLoop, "downloader");
        downloader.start();
    }

    private void stopDownloader() {
        Thread t = downloader;
        downloader = null;
        if (t != null) t.interrupt();
    }

    private void signalDownloader() {
        synchronized (downloadSignal) { downloadSignal.notifyAll(); }
    }

    private void downloadLoop() {
        Thread me = Thread.currentThread();
        while (running && downloader == me) {
            Episode target = null;
            int start = Math.max(index, 0);
            for (int j = start; j < queue.size(); j++) {
                Episode e = queue.get(j);
                // Give up on an episode after 2 failed downloads (it'll stream instead).
                if (!isCached(e) && downloadFailures.getOrDefault(e.number, 0) < 2) {
                    target = e;
                    break;
                }
            }
            int cachedInQueue = 0;
            for (int j = start; j < queue.size(); j++) if (isCached(queue.get(j))) cachedInQueue++;
            int remaining = Math.max(queue.size() - start, 0);

            if (target == null) {
                sDownloadInfo = "All " + remaining + " queued episodes downloaded";
                waitForSignal(5 * 60_000);
                continue;
            }
            if (!isOnWifi()) {
                sDownloadInfo = cachedInQueue + "/" + remaining
                        + " downloaded · waiting for Wi-Fi to download the rest";
                waitForSignal(60_000);
                continue;
            }
            if (!makeRoom()) {
                sDownloadInfo = "Storage full: streaming the rest instead";
                waitForSignal(10 * 60_000);
                continue;
            }
            sDownloadInfo = "Downloading #" + target.number + " (" + cachedInQueue + "/"
                    + remaining + " done)";
            boolean ok = download(target, me);
            if (!ok && running && downloader == me) {
                downloadFailures.merge(target.number, 1, Integer::sum);
                waitForSignal(30_000);
            }
        }
    }

    private void waitForSignal(long ms) {
        synchronized (downloadSignal) {
            try { downloadSignal.wait(ms); } catch (InterruptedException ignored) { }
        }
    }

    private boolean download(Episode ep, Thread me) {
        File dir = episodeDir();
        File part = new File(dir, ep.fileName() + ".part");
        File done = new File(dir, ep.fileName());
        HttpURLConnection conn = null;
        try {
            conn = FeedRepository.Http.open(ep.url);
            long total = conn.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                int r;
                long lastUpdate = 0;
                while ((r = in.read(buf)) != -1) {
                    if (!running || downloader != me) throw new InterruptedException();
                    out.write(buf, 0, r);
                    got += r;
                    long now = System.currentTimeMillis();
                    if (total > 0 && now - lastUpdate > 1000) {
                        lastUpdate = now;
                        sDownloadInfo = String.format(Locale.US, "Downloading #%d: %d%%",
                                ep.number, (int) (got * 100 / total));
                    }
                }
                if (total > 0 && got < total) throw new java.io.IOException("Incomplete download");
            }
            if (!part.renameTo(done)) throw new java.io.IOException("Rename failed");
            main.post(this::updateQueueInfo);
            return true;
        } catch (Exception e) {
            part.delete();
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Keeps the download folder under the cap by deleting the oldest unneeded episodes. */
    private boolean makeRoom() {
        File dir = episodeDir();
        Set<String> keep = new HashSet<>();
        for (int j = Math.max(index, 0); j < queue.size(); j++) keep.add(queue.get(j).fileName());

        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp3"));
        if (files == null) files = new File[0];
        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        int count = list.size();
        for (File f : list) {
            boolean full = count >= settings.maxCached || dir.getUsableSpace() < MIN_FREE_BYTES;
            if (!full) break;
            if (keep.contains(f.getName())) continue;
            if (player != null && index >= 0 && index < queue.size()
                    && f.getName().equals(queue.get(index).fileName())) continue;
            if (f.delete()) count--;
        }
        return count < settings.maxCached && dir.getUsableSpace() >= MIN_FREE_BYTES;
    }

    private void cleanPartials() {
        File[] parts = episodeDir().listFiles((d, name) -> name.endsWith(".part"));
        if (parts != null) for (File f : parts) f.delete();
    }

    private File episodeDir() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        File dir = new File(new File(base, "episodes"), settings.feedKey());
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File cachedFile(Episode e) {
        return new File(episodeDir(), e.fileName());
    }

    private boolean isCached(Episode e) {
        return cachedFile(e).exists();
    }

    // ------------------------------------------------------------------ network

    private boolean isOnWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private boolean hasAnyNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private boolean canStream() {
        if (isOnWifi()) return true;
        return Settings.prefs(this).getBoolean(Settings.K_ALLOW_MOBILE, false) && hasAnyNetwork();
    }

    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            // HIGH_PERF keeps Wi-Fi awake with the screen off (LOW_LATENCY only works screen-on).
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SleepCast:wifi");
            wifiLock.setReferenceCounted(false);
        }
        if (!wifiLock.isHeld()) wifiLock.acquire();
    }

    private void releaseWifiLock() {
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    // ------------------------------------------------------------------ audio focus & noisy

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                if (player != null) player.setVolume(1f, 1f);
                if (pausedForFocus) resume();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (player != null) player.setVolume(0.25f, 0.25f);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS:
                // e.g. an alarm or a call: pause, and pick back up when it's done.
                if (!paused) {
                    pause();
                    pausedForFocus = true;
                }
                break;
            default:
                break;
        }
    };

    private void requestFocus() {
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .setWillPauseWhenDucked(false)
                    .build();
        }
        audioManager.requestAudioFocus(focusRequest);
    }

    private void abandonFocus() {
        if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pause();
        }
    };

    private void registerNoisy() {
        if (noisyRegistered) return;
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisyReceiver, f);
        }
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) { }
        noisyRegistered = false;
    }

    // ------------------------------------------------------------------ UI plumbing

    private void setStatus(String s) {
        sStatus = s;
        if (running) goForeground(s);
    }

    private void updateQueueInfo() {
        if (index < 0) return;
        int cached = 0;
        for (Episode e : queue) if (isCached(e)) cached++;
        sQueueInfo = String.format(Locale.US, "Episode %d of %d in tonight's queue · %d downloaded",
                index + 1, queue.size(), cached);
    }

    private void updateSession(Episode ep) {
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, ep.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, showTitle + " #" + ep.number)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, showTitle)
                .build());
        updatePlaybackState();
    }

    private void updatePlaybackState() {
        int state = paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING;
        long pos = 0;
        try { if (player != null && prepared) pos = player.getCurrentPosition(); }
        catch (Exception ignored) { }
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_STOP)
                .setState(state, pos, paused ? 0f : 1f)
                .build());
        if (running) goForeground(sStatus);
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Playback",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private PendingIntent serviceIntent(String action, int req) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, req, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void goForeground(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_moon)
                .setContentTitle(sNowPlaying.isEmpty() ? getString(R.string.app_name) : sNowPlaying)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, paused ? R.drawable.ic_play : R.drawable.ic_pause),
                        paused ? "Play" : "Pause", serviceIntent(ACTION_TOGGLE, 1)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_next),
                        "Next", serviceIntent(ACTION_NEXT, 2)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stop),
                        "Stop", serviceIntent(ACTION_STOP, 3)).build());
        if (session != null) {
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }
}
