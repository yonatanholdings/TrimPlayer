package de.danoeh.antennapod.storage.preferences;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent, size-capped ring log of discrete playback decisions (segment
 * auto-skips, episode start/end, completePlayback reasons, seek-past-end skip-to-
 * next, playback errors).
 *
 * <p><b>Why this exists:</b> Android's logcat is a small in-memory ring buffer, so
 * by the time a user files a bug report — often hours after a driving session — the
 * relevant lines have rotated out and the report lands with no diagnostics (a
 * behaviour bug leaves no crash-log either). This survives process death on disk and
 * is attached to every bug report so the "player skipped episodes"-class of report
 * can be triaged from the trail instead of a live reproduction. See
 * project_trimplayer_queue_cascade_skip.
 *
 * <p>Only DISCRETE events are logged — never per-tick position updates — so the file
 * stays small and readable. All I/O is best-effort: a logging failure must never
 * disturb playback, so every exception is swallowed.
 */
public final class TrimPlaybackLog {
    private static final String TAG = "TrimPlaybackLog";
    private static final String FILE_NAME = "trim_playback.log";
    // Keep the file bounded. When it grows past MAX_BYTES we rewrite it down to the
    // most recent KEEP_BYTES so the report carries the freshest trail, not the oldest.
    private static final long MAX_BYTES = 64 * 1024;
    private static final int KEEP_BYTES = 40 * 1024;

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private TrimPlaybackLog() {
    }

    private static File file(@NonNull Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    /** Append one timestamped event line. Cheap + best-effort; safe to call from any
     *  thread and from hot playback paths (callers pass only discrete events). */
    public static void log(@Nullable Context ctx, @NonNull String event) {
        if (ctx == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                File f = file(ctx);
                if (f.length() > MAX_BYTES) {
                    trimToTail(f);
                }
                try (FileWriter w = new FileWriter(f, true)) {
                    w.append(TS.format(new Date())).append(' ').append(event).append('\n');
                }
            } catch (Exception e) {
                // Never let diagnostics logging break playback.
                Log.w(TAG, "playback-log append failed: " + e.getMessage());
            }
        }
    }

    /** The full (already size-capped) log, or empty string if none. */
    @NonNull
    public static String readTail(@Nullable Context ctx) {
        if (ctx == null) {
            return "";
        }
        synchronized (LOCK) {
            try {
                File f = file(ctx);
                if (!f.exists() || f.length() == 0) {
                    return "";
                }
                byte[] bytes = Files.readAllBytes(f.toPath());
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                Log.w(TAG, "playback-log read failed: " + e.getMessage());
                return "";
            }
        }
    }

    public static boolean isAvailable(@Nullable Context ctx) {
        if (ctx == null) {
            return false;
        }
        synchronized (LOCK) {
            File f = file(ctx);
            return f.exists() && f.length() > 0;
        }
    }

    private static void trimToTail(@NonNull File f) throws IOException {
        byte[] bytes = Files.readAllBytes(f.toPath());
        if (bytes.length <= KEEP_BYTES) {
            return;
        }
        int start = bytes.length - KEEP_BYTES;
        // Advance to the next line boundary so we don't emit a half line.
        while (start < bytes.length && bytes[start] != '\n') {
            start++;
        }
        if (start < bytes.length) {
            start++;
        }
        try (FileWriter w = new FileWriter(f, false)) {
            w.write("…(trimmed)…\n");
            w.write(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
        }
    }
}
