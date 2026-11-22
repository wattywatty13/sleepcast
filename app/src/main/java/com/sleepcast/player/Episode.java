package com.sleepcast.player;

import org.json.JSONException;
import org.json.JSONObject;

/** One podcast episode from the feed. */
public class Episode {
    public final int number;
    public final String title;
    public final String url;

    public Episode(int number, String title, String url) {
        this.number = number;
        this.title = title;
        this.url = url;
    }

    /** Extension doesn't matter to the player (it sniffs the format), so mp3/m4a/etc all work. */
    public String fileName() {
        return String.format(java.util.Locale.US, "ep_%05d.media", number);
    }

    public String displayTitle() {
        return "#" + number + " · " + title;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("n", number);
        o.put("t", title);
        o.put("u", url);
        return o;
    }

    static Episode fromJson(JSONObject o) throws JSONException {
        return new Episode(o.getInt("n"), o.getString("t"), o.getString("u"));
    }
}
