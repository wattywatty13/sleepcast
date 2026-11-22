package com.sleepcast.player;

import android.content.Context;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads any podcast RSS feed, works out episode numbers, and keeps only the configured range.
 * The raw feed is cached on the phone, so changing the range doesn't need a re-download.
 */
public class FeedRepository {

    /** The result: show name + the numbered episodes inside the configured range. */
    public static class Feed {
        public final String title;
        public final List<Episode> episodes;
        public final String numberingUsed;

        Feed(String title, List<Episode> episodes, String numberingUsed) {
            this.title = title;
            this.episodes = episodes;
            this.numberingUsed = numberingUsed;
        }
    }

    /** One <item> as it appears in the feed, before numbering. */
    static class RawItem {
        String title;
        String url;
        int tagNumber = -1;  // from <itunes:episode> or "#123" in the title; -1 if none
        long date = -1;      // pubDate in ms; -1 if missing/unparseable
        int feedIndex;       // position in the feed (0 = first)
    }

    private static final Pattern HASH_NUMBER = Pattern.compile("#\\s*(\\d{1,5})");
    private static final Pattern EP_NUMBER =
            Pattern.compile("(?i)\\b(?:episode|ep\\.?|podcast)\\s*#?\\s*(\\d{1,5})\\b");
    private static final String[] DATE_FORMATS = {
            "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z",
            "dd MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssXXX"
    };

    public static Feed load(Context ctx, Settings s, boolean forceRefresh) throws Exception {
        File cache = new File(ctx.getFilesDir(),
                "feed_" + Integer.toHexString(s.feedUrl.hashCode()) + ".json");
        String title = null;
        List<RawItem> items = null;

        boolean stale = forceRefresh || !cache.exists()
                || (s.refreshDays > 0 && System.currentTimeMillis() - cache.lastModified()
                > s.refreshDays * 24L * 60 * 60 * 1000);
        if (!stale) {
            try {
                JSONObject o = readCache(cache);
                title = o.optString("title", null);
                items = itemsFromJson(o.getJSONArray("items"));
            } catch (Exception ignored) {
                items = null;
            }
        }
        if (items == null) {
            try {
                Object[] parsed = fetchAndParse(s.feedUrl);
                title = (String) parsed[0];
                @SuppressWarnings("unchecked")
                List<RawItem> fresh = (List<RawItem>) parsed[1];
                items = fresh;
                writeCache(cache, title, items);
            } catch (Exception e) {
                if (!cache.exists()) throw e;
                JSONObject o = readCache(cache); // offline: fall back to the old copy
                title = o.optString("title", null);
                items = itemsFromJson(o.getJSONArray("items"));
            }
        }
        if (items.isEmpty()) throw new IOException("The feed has no playable episodes");
        return number(title == null || title.isEmpty() ? "Podcast" : title, items, s);
    }

    /** Applies the numbering mode and the range. */
    static Feed number(String title, List<RawItem> items, Settings s) {
        String mode = s.numbering;
        if ("auto".equals(mode)) {
            int tagged = 0;
            for (RawItem it : items) if (it.tagNumber > 0) tagged++;
            mode = tagged >= items.size() * 0.8 ? "tag" : "order";
        }

        Map<Integer, Episode> byNumber = new LinkedHashMap<>();
        if ("tag".equals(mode)) {
            for (RawItem it : items) {
                if (it.tagNumber > 0 && s.inRange(it.tagNumber) && !byNumber.containsKey(it.tagNumber)) {
                    byNumber.put(it.tagNumber, new Episode(it.tagNumber, it.title, it.url));
                }
            }
        } else {
            List<RawItem> sorted = new ArrayList<>(items);
            boolean allDated = true;
            for (RawItem it : sorted) if (it.date <= 0) allDated = false;
            if (allDated) {
                sorted.sort((a, b) -> a.date != b.date ? Long.compare(a.date, b.date)
                        : Integer.compare(b.feedIndex, a.feedIndex));
            } else {
                // Feeds normally list newest first, so reverse to get oldest first.
                sorted.sort((a, b) -> Integer.compare(b.feedIndex, a.feedIndex));
            }
            for (int i = 0; i < sorted.size(); i++) {
                int n = i + 1;
                if (s.inRange(n)) byNumber.put(n, new Episode(n, sorted.get(i).title, sorted.get(i).url));
            }
        }
        return new Feed(title, new ArrayList<>(byNumber.values()), mode);
    }

    private static Object[] fetchAndParse(String url) throws Exception {
        HttpURLConnection conn = Http.open(url);
        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            return parse(in);
        } finally {
            conn.disconnect();
        }
    }

    /** Returns {channelTitle, List<RawItem>}. Skips bonus/trailer items and items with no audio. */
    static Object[] parse(InputStream in) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(in, null);

        String channelTitle = null;
        List<RawItem> items = new ArrayList<>();
        boolean inItem = false;
        String title = null, itunesEpisode = null, episodeType = null, enclosure = null, pubDate = null;
        int index = 0;

        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = p.getName();
                if ("item".equals(name) || "entry".equals(name)) {
                    inItem = true;
                    title = itunesEpisode = episodeType = enclosure = pubDate = null;
                } else if (!inItem) {
                    if ("title".equals(name) && channelTitle == null) channelTitle = p.nextText().trim();
                } else {
                    switch (name) {
                        case "title":
                            if (title == null) title = p.nextText().trim();
                            break;
                        case "itunes:episode":
                            itunesEpisode = p.nextText().trim();
                            break;
                        case "itunes:episodeType":
                            episodeType = p.nextText().trim();
                            break;
                        case "pubDate":
                        case "published":
                            pubDate = p.nextText().trim();
                            break;
                        case "enclosure":
                            if (enclosure == null) enclosure = p.getAttributeValue(null, "url");
                            break;
                        case "link": // Atom feeds: <link rel="enclosure" href="...">
                            if (enclosure == null
                                    && "enclosure".equals(p.getAttributeValue(null, "rel"))) {
                                enclosure = p.getAttributeValue(null, "href");
                            }
                            break;
                        case "media:content":
                            if (enclosure == null) {
                                String type = p.getAttributeValue(null, "type");
                                if (type == null || type.startsWith("audio")) {
                                    enclosure = p.getAttributeValue(null, "url");
                                }
                            }
                            break;
                        default:
                            break;
                    }
                }
            } else if (event == XmlPullParser.END_TAG
                    && ("item".equals(p.getName()) || "entry".equals(p.getName()))) {
                inItem = false;
                boolean extra = episodeType != null
                        && (episodeType.equalsIgnoreCase("bonus") || episodeType.equalsIgnoreCase("trailer"));
                if (!extra && enclosure != null && !enclosure.isEmpty()) {
                    RawItem it = new RawItem();
                    it.title = title == null || title.isEmpty() ? "Untitled episode" : title;
                    it.url = enclosure;
                    Integer n = numberFrom(title, itunesEpisode);
                    it.tagNumber = n == null ? -1 : n;
                    it.date = parseDate(pubDate);
                    it.feedIndex = index++;
                    items.add(it);
                }
            }
            event = p.next();
        }
        return new Object[]{channelTitle, items};
    }

    /** The feed's own number: <itunes:episode>, else the last "#123" in the title, else "Episode 123". */
    static Integer numberFrom(String title, String itunesEpisode) {
        if (itunesEpisode != null) {
            try {
                int n = Integer.parseInt(itunesEpisode.trim());
                if (n > 0) return n;
            } catch (NumberFormatException ignored) {
            }
        }
        if (title != null) {
            Matcher m = HASH_NUMBER.matcher(title);
            Integer last = null;
            while (m.find()) last = Integer.parseInt(m.group(1));
            if (last != null && last > 0) return last;
            m = EP_NUMBER.matcher(title);
            if (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n > 0) return n;
            }
        }
        return null;
    }

    static long parseDate(String s) {
        if (s == null || s.isEmpty()) return -1;
        for (String f : DATE_FORMATS) {
            try {
                SimpleDateFormat fmt = new SimpleDateFormat(f, Locale.US);
                fmt.setLenient(true);
                java.util.Date d = fmt.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    // ---- cache ----

    private static JSONObject readCache(File f) throws Exception {
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < data.length) {
                int r = in.read(data, off, data.length - off);
                if (r < 0) break;
                off += r;
            }
        }
        return new JSONObject(new String(data, StandardCharsets.UTF_8));
    }

    private static List<RawItem> itemsFromJson(JSONArray arr) throws Exception {
        List<RawItem> list = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            RawItem it = new RawItem();
            it.title = o.getString("t");
            it.url = o.getString("u");
            it.tagNumber = o.getInt("n");
            it.date = o.getLong("d");
            it.feedIndex = o.getInt("i");
            list.add(it);
        }
        return list;
    }

    private static void writeCache(File f, String title, List<RawItem> items) throws Exception {
        JSONArray arr = new JSONArray();
        for (RawItem it : items) {
            JSONObject o = new JSONObject();
            o.put("t", it.title);
            o.put("u", it.url);
            o.put("n", it.tagNumber);
            o.put("d", it.date);
            o.put("i", it.feedIndex);
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("title", title == null ? "" : title);
        root.put("items", arr);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("Could not save the feed");
    }

    /** Tiny HTTP helper that follows redirects across http/https (podcast hosts redirect a lot). */
    static class Http {
        static HttpURLConnection open(String url) throws IOException {
            String current = url;
            for (int hops = 0; hops < 10; hops++) {
                HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "SleepCast/1.0 (Android podcast player)");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null) throw new IOException("Redirect without location");
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                if (code != 200) {
                    c.disconnect();
                    throw new IOException("HTTP " + code + " for " + current);
                }
                return c;
            }
            throw new IOException("Too many redirects");
        }
    }
}
