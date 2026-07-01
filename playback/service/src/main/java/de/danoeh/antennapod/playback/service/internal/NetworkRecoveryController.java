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
        /**
         * Run {@code retry} after {@code delayMs}. A prior pending retry is replaced.
         */
        void scheduleRetry(long delayMs, Runnable retry);

        /** Cancel any pending retry. */
        void cancelScheduled();

        /** Keep the UI in a buffering (not stalled/stopped) state while recovering. */
        void onBuffering();

        /** Recovery budget exhausted — surface the error as fatal. */
        void onExhausted();
    }

    /**
     * How close the post-{@code prepare()} position must already be to the captured resume position
     * for us to treat it as "retained" and skip the restore seek. A plain mid-playback stall retains
     * the position (≈ equal); the error-during-seek case drops it toward 0 (far below).
     */
    private static final long POSITION_RETAINED_TOLERANCE_MS = 10_000;

    private final Player player;
    private final Host host;
    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;

    private int attempts = 0;
    private long resumePositionMs = -1;
    // True between a recovery re-prepare and the player next reaching a ready state — the window in
    // which we owe the captured position a restore (done in onPlayerReady, where the seek can land).
    private boolean restorePending = false;

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
        // Re-prepare, but do NOT seek here. Seeking a progressive stream into a region that isn't
        // buffered — exactly the case while still offline mid-recovery — does not error: ExoPlayer's
        // ProgressiveMediaPeriod silently resolves the seek to position ~0, restarting the episode
        // from the beginning. An unconditional re-seek here therefore *causes* the restart-from-0 it
        // was meant to prevent. We instead defer the restore to onPlayerReady, which only fires once
        // the source is actually loadable (back online), where the seek lands where asked.
        player.prepare();
        restorePending = true;
    }

    /**
     * A successful (re)buffer to a ready state confirms recovery: restore the captured position if the
     * re-prepare dropped it, then give the next outage a fresh budget. Reaching ready means the source
     * is loadable (we are back online), so a restore seek here resolves correctly instead of collapsing
     * to 0 the way a blind offline seek would.
     */
    public void onPlayerReady() {
        if (restorePending
                && resumePositionMs > 0
                && player.getCurrentPositionMs() < resumePositionMs - POSITION_RETAINED_TOLERANCE_MS) {
            // Position was lost (prepare reset it, or a previous restore seek collapsed). Restore it —
            // but stay "pending" and keep the attempt budget until the host confirms the seek actually
            // landed (onRestoreSeekSucceeded). The restore seek can ITSELF collapse to ~0 if the target
            // is momentarily un-servable (a transient ready on a flaky reconnect), and we must not
            // declare recovery complete in that case.
            player.seekTo(resumePositionMs);
            return;
        }
        // Either nothing to restore (no captured position) or prepare() retained it — a plain stall.
        restorePending = false;
        attempts = 0;
    }

    /** The host confirms the restore seek landed at (near) the captured position — recovery is done. */
    public void onRestoreSeekSucceeded() {
        restorePending = false;
        attempts = 0;
    }

    /**
     * The host reports the restore seek collapsed (the target was still un-servable, so it resolved to
     * ~0). Keep the recovery alive with a backed-off re-prepare instead of stranding playback at the
     * start — the next ready retries the restore — bounded by the same attempt budget. No-op if no
     * restore is in flight.
     */
    public void onRestoreSeekCollapsed() {
        if (!restorePending) {
            return;
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

    /**
     * The OS reports a usable network is available again. If a recovery is in flight, stop waiting out
     * the backoff timer and re-prepare immediately; otherwise do nothing. This turns the up-to-
     * {@code maxDelayMs} silent gap (the player blindly waiting for the next scheduled tick) into a
     * near-instant resume the moment connectivity returns.
     *
     * <p>The resume position captured at the start of the sequence is preserved — re-reading the
     * player position now could pick up a post-failure 0. The attempt counter is reset to 1 (not 0) so
     * that position is kept, while a subsequent failure backs off from near the base delay again
     * instead of from the current cap.
     *
     * <p>Must be called on the same thread as {@link #onRecoverableError} (the main thread in the real
     * host) so the controller's state is only ever touched from one thread.
     */
    public void onConnectivityRestored() {
        if (attempts == 0) {
            return; // No recovery in flight; nothing to accelerate.
        }
        host.cancelScheduled();
        attempts = 1;
        host.onBuffering();
        host.scheduleRetry(0, this::recover);
    }

    /** Abandon any in-flight recovery (new media, teardown, stop). */
    public void reset() {
        attempts = 0;
        resumePositionMs = -1;
        restorePending = false;
        host.cancelScheduled();
    }
}
