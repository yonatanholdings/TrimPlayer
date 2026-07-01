package de.danoeh.antennapod.playback.service.trim;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight persistent log of playback resilience events (network-recovery, player errors, and
 * buffering stalls), written to a file in the app's own external-files dir so a silence that
 * happens in real, untethered use can be diagnosed afterwards.
 *
 * <p>This exists because the system logcat ring buffer only retains a couple of minutes — a stall
 * the user reports hours later is long gone — and an {@code adb}-spawned capture dies the moment
 * the phone is unplugged. Persisting the handful of high-signal events the player already logs
 * survives unplugging and reboot and needs no host connection: just
 * {@code adb pull /sdcard/Android/data/&lt;pkg&gt;/files/diag} (or pull it from the device UI).
 *
 * <p>Writes are serialized on a single low-priority background thread (the callers run on the
 * player/main thread, and these events are rare), and the file is size-capped with one rollover so
 * it can never grow without bound.
 */
public final class PlaybackDiagnostics {
    private static final String TAG = "PlaybackDiagnostics";
    private static final String DIR = "diag";
    private static final String FILE = "playback-diag.log";
    // Roll over to ".1" past this size; at most 2 files => <= 2 MB on disk.
    private static final long MAX_BYTES = 1024 * 1024;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "playback-diag");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // ThreadLocal.withInitial is API 26+; use an anonymous subclass so it works on minSdk 23.
    private static final ThreadLocal<SimpleDateFormat> TS = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
        }
    };

    private PlaybackDiagnostics() {
    }

    /** Append one timestamped line. Best-effort: any failure is swallowed (diagnostics must never
     *  affect playback). {@code tag} groups the source, {@code message} is free-form. */
    public static void log(@Nullable Context context, String tag, String message) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final long now = System.currentTimeMillis();
        EXEC.execute(() -> write(app, now, tag, message));
    }

    private static void write(Context app, long now, String tag, String message) {
        try {
            // getExternalFilesDir may return null when external storage isn't mounted; fall back to
            // internal storage. (new File((File) null, child) would silently become a relative path.)
            File base = app.getExternalFilesDir(null);
            if (base == null) {
                base = app.getFilesDir();
            }
            File dir = new File(base, DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File file = new File(dir, FILE);
            if (file.length() > MAX_BYTES) {
                File prev = new File(dir, FILE + ".1");
                if (prev.exists() && !prev.delete()) {
                    Log.w(TAG, "Could not delete old diag rollover");
                }
                if (!file.renameTo(prev)) {
                    Log.w(TAG, "Could not roll over diag log");
                }
            }
            try (Writer w = new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.append(TS.get().format(new Date(now)))
                        .append(' ').append(tag).append(": ").append(message).append('\n');
            }
        } catch (Throwable t) {
            // Never let diagnostics throw into the playback path.
            Log.w(TAG, "diag write failed: " + t.getMessage());
        }
    }
}
