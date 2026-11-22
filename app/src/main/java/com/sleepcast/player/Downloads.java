package com.sleepcast.player;

import android.content.Context;

import java.io.File;
import java.util.Locale;

/** Where downloaded episodes live, how much space they take, and clearing them. */
public final class Downloads {
    private Downloads() {
    }

    /** Root folder for all feeds' downloads (each feed gets its own subfolder). */
    public static File root(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        return new File(base, "episodes");
    }

    /** {totalBytes, episodeCount} across every feed. */
    public static long[] stats(Context ctx) {
        long[] out = new long[2];
        walk(root(ctx), out, false);
        return out;
    }

    /** Deletes every downloaded episode. Returns how many were removed. */
    public static int clearAll(Context ctx) {
        long[] out = new long[2];
        walk(root(ctx), out, true);
        return (int) out[1];
    }

    private static void walk(File dir, long[] out, boolean delete) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                walk(f, out, delete);
            } else if (f.getName().endsWith(".media") || f.getName().endsWith(".part")) {
                if (f.getName().endsWith(".media")) {
                    out[0] += f.length();
                    out[1]++;
                }
                if (delete) f.delete();
            }
        }
    }

    public static String formatBytes(long b) {
        if (b < 1024L * 1024) return Math.max(0, b / 1024) + " KB";
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.0f MB", b / 1048576.0);
        return String.format(Locale.US, "%.1f GB", b / 1073741824.0);
    }
}
