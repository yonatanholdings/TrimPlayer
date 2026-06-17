package de.danoeh.antennapod.playback.service.internal;

/**
 * Drives recovery from a transient network error while streaming: instead of surfacing a fatal
 * error (which stops playback until the user restarts it), it re-prepares the player with capped
 * exponential backoff so playback resumes on its own once connectivity returns.
 *
 * <p>Pure Java (no Android, no media3) so the recovery state machine — attempt counting, backoff,
 * and crucially the resume-position handling — is unit-testable on the JVM. {@link ExoPlayerWrapper}
 * adapts it to the real {@code ExoPlayer} + main-thread {@code Handler}.
 *
 * <p><b>Why the position handling matters:</b> {@code ExoPlayer.prepare()} is documented to retain
 * the current position for a steady-state stall, but when the error fires <em>during a forward seek
 * into an un-buffered region of a progressive stream</em> — exactly what a trim ad-skip does — the
 * seek target was never resolved into a loaded media period, so re-preparing resumes the source from
 * its default position (0). The controller therefore captures the good position once, at the start of
 * a recovery sequence, and re-seeks to it after every {@code prepare()}.
 */
public final class NetworkRecoveryController {

    /** The minimal slice of the player the recovery state machine drives. */
    public interface Player {
        long getCurrentPositionMs();

        void prepare();

        void seekTo(long positionMs);
    }

    /** Side effects the controller delegates to the host (scheduling, UI, terminal error). */
    public interface Host {
        /** Run {@code retry} after {@code delayMs}. A prior pending retry is replaced. */
        void scheduleRetry(long delayMs, Runnable retry);

        /** Cancel any pending retry. */
        void cancelScheduled();

        /** Keep the UI in a buffering (not stalled/stopped) state while recovering. */
        void onBuffering();

        /** Recovery budget exhausted — surface the error as fatal. */
        void onExhausted();
    }

    private final Player player;
    private final Host host;
    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;

    private int attempts = 0;
    private long resumePositionMs = -1;

    public NetworkRecoveryController(Player player, Host host,
                                     int maxAttempts, long baseDelayMs, long maxDelayMs) {
        this.player = player;
        this.host = host;
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    /**
     * Report a recoverable network error. Schedules a backed-off re-prepare, or surfaces the error
     * once the attempt budget is spent.
     */
    public void onRecoverableError() {
        if (attempts == 0) {
            // Capture the good position once, at the start of a recovery sequence — before any
            // re-prepare can lose it. Re-capturing on later attempts would read the post-failure
            // (possibly zeroed) position and defeat the restore.
            resumePositionMs = player.getCurrentPositionMs();
        }
        if (attempts >= maxAttempts) {
            host.onExhausted();
            reset();
            return;
        }
        attempts++;
        long delay = Math.min(maxDelayMs, baseDelayMs * (1L << (attempts - 1)));
        host.onBuffering();
        host.scheduleRetry(delay, this::recover);
    }

    private void recover() {
        player.prepare();
        // prepare() does NOT reliably retain the position when the error interrupted a forward seek
        // into un-buffered stream, so restore the position we captured before the outage. Seeking to
        // 0 would be a no-op (and meaningless), so guard against it.
        if (resumePositionMs > 0) {
            player.seekTo(resumePositionMs);
        }
    }

    /** A successful (re)buffer to a ready state confirms recovery: the next outage gets a fresh budget. */
    public void onPlayerReady() {
        attempts = 0;
    }

    /** Abandon any in-flight recovery (new media, teardown, stop). */
    public void reset() {
        attempts = 0;
        resumePositionMs = -1;
        host.cancelScheduled();
    }
}
